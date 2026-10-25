//! WASAPI event-driven output. Request the device's minimum shared engine period.
use crate::audio::Playout;
use anyhow::{bail, Result};
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc,
    },
    thread,
};
use wasapi::{DeviceEnumerator, Direction, SampleType, StreamMode, TimingMode, WaveFormat};

pub struct Playback {
    stop: Arc<AtomicBool>,
    worker: Option<thread::JoinHandle<()>>,
    errors: mpsc::Receiver<String>,
}
impl Playback {
    pub fn start(name: &str, player: Playout) -> Result<Self> {
        let name = name.to_owned();
        let stop = Arc::new(AtomicBool::new(false));
        let stopping = stop.clone();
        let (ready_tx, ready_rx) = mpsc::sync_channel(1);
        let (errors_tx, errors) = mpsc::channel();
        let worker = thread::spawn(move || {
            let result = run(&name, player, &stopping, &ready_tx);
            if let Err(e) = result {
                let message = format!("Audio output: {e:#}");
                let _ = ready_tx.try_send(Err(message.clone()));
                let _ = errors_tx.send(message);
            }
        });
        let playback = Self {
            stop,
            worker: Some(worker),
            errors,
        };
        ready_rx.recv()?.map_err(anyhow::Error::msg)?;
        Ok(playback)
    }
    pub fn check(&self) -> Result<()> {
        if let Ok(error) = self.errors.try_recv() {
            bail!(error);
        }
        Ok(())
    }
}
impl Drop for Playback {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}
fn run(
    name: &str,
    mut player: Playout,
    stop: &AtomicBool,
    ready: &mpsc::SyncSender<Result<(), String>>,
) -> Result<()> {
    wasapi::initialize_mta().ok()?;
    struct Com;
    impl Drop for Com {
        fn drop(&mut self) {
            wasapi::deinitialize();
        }
    }
    let _com = Com;
    let devices = DeviceEnumerator::new()?.get_device_collection(&Direction::Render)?;
    let device = devices.get_device_with_name(name)?;
    let mut client = device.get_iaudioclient()?;
    let mix = client.get_mixformat()?;
    let channels = mix.get_nchannels() as usize;
    let format = WaveFormat::new(
        32,
        32,
        &SampleType::Float,
        48_000,
        channels,
        Some(mix.get_dwchannelmask()),
    );
    // No exclusive lock on the cable: Discord, games and diagnostics can coexist.
    let low_latency = client
        .get_shared_mode_engine_period(&format)
        .and_then(|period| {
            client.initialize_shared_audio_stream(&format, TimingMode::Events, period.min)
        });
    if low_latency.is_err() {
        // Failed initialization can leave an audio client unusable. Start with a fresh one.
        client = device.get_iaudioclient()?;
        let (period, _) = client.get_device_period()?;
        client.initialize_client(
            &format,
            &Direction::Render,
            &StreamMode::EventsShared {
                autoconvert: true,
                buffer_duration_hns: period,
            },
        )?;
    }
    let event = client.set_get_eventhandle()?;
    let render = client.get_audiorenderclient()?;
    let capacity = client.get_buffer_size()? as usize;
    let mut samples = vec![0f32; capacity * channels];
    let mut bytes = vec![0u8; samples.len() * 4];
    render.write_to_device(capacity, &bytes, None)?;
    client.start_stream()?;
    let _ = ready.send(Ok(()));
    let result = (|| -> Result<()> {
        while !stop.load(Ordering::Acquire) {
            event.wait_for_event(1000)?;
            let frames = client.get_available_space_in_frames()? as usize;
            if frames == 0 {
                continue;
            }
            if frames > capacity {
                bail!("Audio buffer changed unexpectedly");
            }
            let count = frames * channels;
            player.render(&mut samples[..count], channels);
            for (sample, data) in samples[..count]
                .iter()
                .zip(bytes.as_chunks_mut::<4>().0.iter_mut())
            {
                data.copy_from_slice(&sample.to_le_bytes());
            }
            render.write_to_device(frames, &bytes[..count * 4], None)?;
        }
        Ok(())
    })();
    let _ = client.stop_stream();
    result
}
