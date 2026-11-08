//! Live CABLE Output meter with an optional short, in-memory record/playback check.
use anyhow::{bail, Context, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use rtrb::{Producer, RingBuffer};
use std::{
    sync::{
        atomic::{AtomicBool, AtomicU32, Ordering},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};

const SECONDS: usize = 3;
pub const BARS: usize = 80;
const BAR_INTERVAL: Duration = Duration::from_millis(50);

/// Four seconds of display history; this never buffers or delays the audio path.
struct Meter {
    since: Instant,
    peak: f32,
}
impl Meter {
    fn new(now: Instant) -> Self {
        Self {
            since: now,
            peak: 0.,
        }
    }
    fn update(&mut self, now: Instant, peak: f32) -> Option<f32> {
        self.peak = self.peak.max(peak);
        if now.duration_since(self.since) < BAR_INTERVAL {
            return None;
        }
        self.since = now;
        Some(std::mem::take(&mut self.peak))
    }
}

#[derive(Clone, Copy, PartialEq)]
pub enum Phase {
    Preparing,
    Monitoring,
    Recording,
    Playing,
    Failed,
}
impl Phase {
    pub fn active(self) -> bool {
        matches!(
            self,
            Self::Preparing | Self::Monitoring | Self::Recording | Self::Playing
        )
    }
    pub fn testing(self) -> bool {
        matches!(self, Self::Recording | Self::Playing)
    }
}

#[derive(Clone)]
pub struct Snapshot {
    pub phase: Phase,
    pub message: String,
    pub waveform: [f32; BARS],
}

struct State {
    cancelled: AtomicBool,
    record_requested: AtomicBool,
    snapshot: Mutex<Snapshot>,
}
impl State {
    fn publish(&self, phase: Phase, message: &str) {
        let mut state = self.snapshot.lock().unwrap();
        if !self.cancelled.load(Ordering::Relaxed) {
            state.phase = phase;
            state.message = message.into();
        }
    }
    fn level(&self, value: f32) {
        let mut state = self.snapshot.lock().unwrap();
        state.waveform.copy_within(1.., 0);
        state.waveform[BARS - 1] = value;
    }
    fn cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Relaxed)
    }
}

pub struct MicrophoneTest {
    state: Arc<State>,
}
impl MicrophoneTest {
    pub fn start() -> Self {
        let state = Arc::new(State {
            cancelled: AtomicBool::new(false),
            record_requested: AtomicBool::new(false),
            snapshot: Mutex::new(Snapshot {
                phase: Phase::Preparing,
                message: "Подготовка CABLE Output…".into(),
                waveform: [0.; BARS],
            }),
        });
        let worker = state.clone();
        thread::spawn(move || {
            if let Err(error) = run(&worker) {
                worker.publish(Phase::Failed, &format!("{error:#}"));
            }
        });
        Self { state }
    }
    pub fn snapshot(&self) -> Snapshot {
        self.state.snapshot.lock().unwrap().clone()
    }
    pub fn record(&self) {
        self.state.record_requested.store(true, Ordering::Relaxed);
    }
}
impl Drop for MicrophoneTest {
    fn drop(&mut self) {
        self.state.cancelled.store(true, Ordering::Relaxed);
    }
}

fn virtual_output(name: &str) -> bool {
    let name = name.to_lowercase();
    ["cable", "voicemeeter", "virtual", "vb-audio"]
        .iter()
        .any(|part| name.contains(part))
}

fn run(state: &State) -> Result<()> {
    let host = cpal::default_host();
    let mut meter = Meter::new(Instant::now());
    let mut message = "CABLE Output · звук с телефона в реальном времени".to_owned();
    while !state.cancelled() {
        let input = host
            .input_devices()?
            .find(|device| {
                device
                    .name()
                    .unwrap_or_default()
                    .to_lowercase()
                    .contains("cable output")
            })
            .context("CABLE Output не найден. Установите компонент микрофона")?;
        let capture = input.default_input_config()?;
        let rate = capture.sample_rate().0;
        if !(8000..=192000).contains(&rate) || capture.channels() == 0 || capture.channels() > 8 {
            bail!("Неподдерживаемый формат CABLE Output");
        }
        let limit = rate as usize * SECONDS;
        let (producer, mut consumer) = RingBuffer::new(rate as usize / 2);
        let peak = Arc::new(AtomicU32::new(0));
        let failed = Arc::new(AtomicBool::new(false));
        let mut config = capture.config();
        if let cpal::SupportedBufferSize::Range { min, max } = *capture.buffer_size() {
            config.buffer_size = cpal::BufferSize::Fixed(256u32.clamp(min, max));
        }
        let stream = match capture.sample_format() {
            cpal::SampleFormat::F32 => {
                record::<f32>(&input, &config, producer, peak.clone(), failed.clone())?
            }
            cpal::SampleFormat::I16 => {
                record::<i16>(&input, &config, producer, peak.clone(), failed.clone())?
            }
            cpal::SampleFormat::U16 => {
                record::<u16>(&input, &config, producer, peak.clone(), failed.clone())?
            }
            _ => bail!("CABLE Output: выберите формат Float32 или PCM16"),
        };
        if state.cancelled() {
            return Ok(());
        }
        state.publish(Phase::Monitoring, &message);
        stream.play()?;
        let mut samples = Vec::new();
        let mut recording = None;
        let mut last_data = Instant::now();
        let mut recorded_peak = 0f32;
        loop {
            if state.cancelled() {
                return Ok(());
            }
            if failed.load(Ordering::Relaxed) {
                bail!("CABLE Output отключён или буфер переполнен");
            }
            if last_data.elapsed() > Duration::from_secs(6) {
                bail!("CABLE Output не передаёт данные");
            }
            if state.record_requested.swap(false, Ordering::Relaxed) && recording.is_none() {
                while consumer.pop().is_ok() {}
                samples = Vec::with_capacity(limit);
                recorded_peak = 0.;
                recording = Some(Instant::now());
                state.publish(Phase::Recording, "Говорите · запись 3 с с CABLE Output");
            }
            let mut received = false;
            while let Ok(sample) = consumer.pop() {
                received = true;
                if recording.is_some() && samples.len() < limit {
                    recorded_peak = recorded_peak.max(sample.abs());
                    samples.push(sample);
                }
            }
            if received {
                last_data = Instant::now();
            }
            if let Some(level) = meter.update(
                Instant::now(),
                f32::from_bits(peak.swap(0, Ordering::Relaxed)),
            ) {
                state.level(level);
            }
            if samples.len() == limit {
                break;
            }
            if recording.is_some_and(|began| began.elapsed() > Duration::from_secs(6)) {
                bail!("Запись CABLE Output прервана");
            }
            thread::sleep(Duration::from_millis(10));
        }
        // Capture closes before replay, so our test cannot record its own speaker output.
        drop(stream);
        message = if recorded_peak < 0.0001 {
            "Сигнала нет · проверьте микрофон телефона".into()
        } else {
            match replay(state, &host, samples, rate, &mut meter) {
                Ok(()) => "Готово · снова прямой сигнал CABLE Output".into(),
                Err(error) => format!("{error:#}"),
            }
        };
    }
    Ok(())
}

fn replay(
    state: &State,
    host: &cpal::Host,
    samples: Vec<f32>,
    rate: u32,
    meter: &mut Meter,
) -> Result<()> {
    if state.cancelled() {
        return Ok(());
    }
    let output = host
        .default_output_device()
        .context("Выберите динамики или наушники в Windows")?;
    let output_name = output.name()?;
    if virtual_output(&output_name) {
        bail!("Для прослушивания выберите динамики или наушники выходом Windows");
    }
    let render = output.default_output_config()?;
    if render.sample_rate().0 == 0 || render.channels() == 0 {
        bail!("Неподдерживаемый формат динамиков");
    }
    let done = Arc::new(AtomicBool::new(false));
    let failed = Arc::new(AtomicBool::new(false));
    let peak = Arc::new(AtomicU32::new(0));
    let config = render.config();
    let stream = match render.sample_format() {
        cpal::SampleFormat::F32 => playback::<f32>(
            &output,
            &config,
            samples,
            rate,
            peak.clone(),
            done.clone(),
            failed.clone(),
        )?,
        cpal::SampleFormat::I16 => playback::<i16>(
            &output,
            &config,
            samples,
            rate,
            peak.clone(),
            done.clone(),
            failed.clone(),
        )?,
        cpal::SampleFormat::U16 => playback::<u16>(
            &output,
            &config,
            samples,
            rate,
            peak.clone(),
            done.clone(),
            failed.clone(),
        )?,
        _ => bail!("Динамики: выберите формат Float32 или PCM16"),
    };
    if state.cancelled() {
        return Ok(());
    }
    state.publish(Phase::Playing, &format!("Прослушивание · {output_name}"));
    stream.play()?;
    let started = Instant::now();
    while !done.load(Ordering::Relaxed) {
        if state.cancelled() {
            return Ok(());
        }
        if failed.load(Ordering::Relaxed) {
            bail!("Динамики отключены во время проверки");
        }
        if started.elapsed() > Duration::from_secs(6) {
            bail!("Динамики не воспроизводят запись");
        }
        if let Some(level) = meter.update(
            Instant::now(),
            f32::from_bits(peak.swap(0, Ordering::Relaxed)),
        ) {
            state.level(level);
        }
        thread::sleep(Duration::from_millis(10));
    }
    Ok(())
}

fn record<T: cpal::SizedSample>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    mut producer: Producer<f32>,
    peak: Arc<AtomicU32>,
    failed: Arc<AtomicBool>,
) -> Result<cpal::Stream>
where
    f32: cpal::FromSample<T>,
{
    let channels = config.channels as usize;
    let overflow = failed.clone();
    Ok(device.build_input_stream(
        config,
        move |data: &[T], _| {
            let mut level = 0f32;
            for frame in data.chunks_exact(channels) {
                let value = frame
                    .iter()
                    .map(|&v| cpal::Sample::to_sample::<f32>(v))
                    .sum::<f32>()
                    / channels as f32;
                let value = if value.is_finite() {
                    value.clamp(-1., 1.)
                } else {
                    0.
                };
                if producer.push(value).is_err() {
                    overflow.store(true, Ordering::Relaxed);
                    break;
                }
                level = level.max(value.abs());
            }
            peak.fetch_max(level.to_bits(), Ordering::Relaxed);
        },
        move |_| {
            failed.store(true, Ordering::Relaxed);
        },
        None,
    )?)
}

fn interpolated(samples: &[f32], position: f64) -> f32 {
    let index = position as usize;
    let Some(&a) = samples.get(index) else {
        return 0.;
    };
    let b = samples.get(index + 1).copied().unwrap_or(a);
    a + (b - a) * position.fract() as f32
}

fn playback<T: cpal::SizedSample + cpal::FromSample<f32>>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    samples: Vec<f32>,
    rate: u32,
    peak: Arc<AtomicU32>,
    done: Arc<AtomicBool>,
    failed: Arc<AtomicBool>,
) -> Result<cpal::Stream> {
    let channels = config.channels as usize;
    let step = rate as f64 / config.sample_rate.0 as f64;
    let mut position = 0.;
    let mut ended = None;
    Ok(device.build_output_stream(
        config,
        move |data: &mut [T], info| {
            let mut level = 0f32;
            for frame in data.chunks_mut(channels) {
                let value = interpolated(&samples, position);
                frame.fill(T::from_sample(value));
                position += step;
                level = level.max(value.abs());
            }
            peak.fetch_max(level.to_bits(), Ordering::Relaxed);
            let timestamp = info.timestamp();
            if let Some(last_playback) = ended {
                if timestamp.callback >= last_playback {
                    done.store(true, Ordering::Relaxed);
                }
            } else if position >= samples.len() as f64 {
                // Wait until the final submitted buffer has played, rather than truncating its tail.
                ended = timestamp.playback.add(Duration::from_secs_f64(
                    data.len() as f64 / channels as f64 / (rate as f64 / step),
                ));
            }
        },
        move |_| {
            failed.store(true, Ordering::Relaxed);
        },
        None,
    )?)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn meter_keeps_transient_peaks_but_advances_only_every_fifty_ms() {
        let start = Instant::now();
        let mut meter = Meter::new(start);
        assert_eq!(meter.update(start + Duration::from_millis(10), 0.7), None);
        assert_eq!(meter.update(start + Duration::from_millis(40), 0.1), None);
        assert_eq!(meter.update(start + BAR_INTERVAL, 0.2), Some(0.7));
        assert_eq!(meter.update(start + BAR_INTERVAL * 2, 0.1), Some(0.1));
        assert_eq!(meter.update(start + BAR_INTERVAL * 3, 0.), Some(0.));
    }
    #[test]
    fn playback_refuses_virtual_routes() {
        for name in [
            "CABLE Input (VB-Audio)",
            "VoiceMeeter Input",
            "Virtual Audio Cable",
        ] {
            assert!(virtual_output(name));
        }
        assert!(!virtual_output("Headphones (Realtek Audio)"));
    }
    #[test]
    fn playback_interpolates_and_stops_at_end() {
        assert_eq!(interpolated(&[0., 1., 0.], 0.5), 0.5);
        assert_eq!(interpolated(&[0., 1., 0.], 1.5), 0.5);
        assert_eq!(interpolated(&[0., 1.], 2.), 0.);
        assert_eq!(interpolated(&[], 0.), 0.);
    }
}
