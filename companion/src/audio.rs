#[cfg(not(windows))]
use anyhow::Context;
use anyhow::{bail, Result};
#[cfg(not(windows))]
use cpal::traits::StreamTrait;
use cpal::traits::{DeviceTrait, HostTrait};
use prost::Message;
use rtrb::{Consumer, Producer, RingBuffer};
use std::{
    io::{Read, Write},
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
};

const RATE: usize = 48_000;
const TARGET: usize = RATE / 100; // One 10 ms packet; do not add a second packet of latency.
const MAX_QUEUED: usize = RATE * 70 / 1000;
#[derive(Clone, PartialEq, Message)]
struct Packet {
    #[prost(bytes = "vec", tag = "1")]
    buffer: Vec<u8>,
    #[prost(uint32, tag = "2")]
    sample_rate: u32,
    #[prost(uint32, tag = "3")]
    channel_count: u32,
    #[prost(uint32, tag = "4")]
    audio_format: u32,
}
fn decode(packet: &[u8]) -> Result<Vec<f32>> {
    let p = Packet::decode(packet)?;
    if p.sample_rate != RATE as u32 || p.channel_count != 1 {
        bail!("Expected 48 kHz mono");
    }
    let width = match p.audio_format {
        2 => 2,
        4 => 4,
        _ => bail!("Unsupported PCM format"),
    };
    if p.buffer.is_empty() || p.buffer.len() % width != 0 || p.buffer.len() / width > 2400 {
        bail!("Invalid audio frame");
    }
    let samples: Vec<_> = p
        .buffer
        .chunks_exact(width)
        .map(|b| {
            if width == 4 {
                f32::from_le_bytes(b.try_into().unwrap())
            } else {
                i16::from_le_bytes(b.try_into().unwrap()) as f32 / 32768.0
            }
        })
        .collect();
    if samples.iter().any(|v| !v.is_finite()) {
        bail!("Non-finite audio sample");
    }
    Ok(samples)
}

pub fn outputs() -> Result<Vec<String>> {
    Ok(cpal::default_host()
        .output_devices()?
        .filter_map(|d| d.name().ok())
        .collect())
}
pub(crate) struct Playout {
    input: Consumer<f32>,
    started: bool,
    current: f32,
    next: f32,
    phase: f64,
    dropped: Arc<AtomicU64>,
}
impl Playout {
    pub(crate) fn new(input: Consumer<f32>, dropped: Arc<AtomicU64>) -> Self {
        Self {
            input,
            started: false,
            current: 0.,
            next: 0.,
            phase: 0.,
            dropped,
        }
    }
    pub(crate) fn render<T: cpal::Sample + cpal::FromSample<f32>>(
        &mut self,
        data: &mut [T],
        channels: usize,
    ) {
        if self.input.slots() > MAX_QUEUED {
            while self.input.slots() > TARGET {
                let _ = self.input.pop();
                self.dropped.fetch_add(1, Ordering::Relaxed);
            }
            self.started = false;
        }
        if !self.started {
            if self.input.slots() < TARGET {
                data.fill(T::from_sample(0.0));
                return;
            }
            self.current = self.input.pop().unwrap_or(0.0);
            self.next = self.input.pop().unwrap_or(0.0);
            self.phase = 0.0;
            self.started = true;
        }
        // Bounded correction of independent phone and sound-card clocks, no callback allocation/locks.
        let ratio = 1.0
            + ((self.input.slots() as f64 - TARGET as f64) / RATE as f64 * 0.02)
                .clamp(-0.003, 0.003);
        for frame in data.chunks_mut(channels) {
            let value = if self.started {
                self.current + (self.next - self.current) * self.phase as f32
            } else {
                0.0
            };
            frame.fill(T::from_sample(value.clamp(-1.0, 1.0)));
            self.phase += ratio;
            while self.phase >= 1.0 && self.started {
                self.phase -= 1.0;
                self.current = self.next;
                match self.input.pop() {
                    Ok(v) => self.next = v,
                    Err(_) => {
                        self.started = false;
                        self.current = 0.0;
                        self.next = 0.0;
                    }
                }
            }
        }
    }
}
#[cfg(not(windows))]
fn stream(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    format: cpal::SampleFormat,
    consumer: Consumer<f32>,
    dropped: Arc<AtomicU64>,
) -> Result<cpal::Stream> {
    let mut player = Playout::new(consumer, dropped);
    let channels = config.channels as usize;
    let error = |e| eprintln!("Audio output: {e}");
    Ok(match format {
        cpal::SampleFormat::F32 => device.build_output_stream(
            config,
            move |d: &mut [f32], _| player.render(d, channels),
            error,
            None,
        )?,
        cpal::SampleFormat::I16 => device.build_output_stream(
            config,
            move |d: &mut [i16], _| player.render(d, channels),
            error,
            None,
        )?,
        _ => bail!("Output must support Float32 or PCM16"),
    })
}
#[cfg(not(windows))]
fn portable_stream(
    output: &str,
    consumer: Consumer<f32>,
    dropped: Arc<AtomicU64>,
) -> Result<cpal::Stream> {
    let device = cpal::default_host()
        .output_devices()?
        .find(|d| d.name().ok().as_deref() == Some(output))
        .context("Selected audio output is unavailable")?;
    let range = device
        .supported_output_configs()?
        .find(|c| {
            c.min_sample_rate().0 <= RATE as u32
                && c.max_sample_rate().0 >= RATE as u32
                && matches!(
                    c.sample_format(),
                    cpal::SampleFormat::F32 | cpal::SampleFormat::I16
                )
        })
        .context("Output does not support 48 kHz PCM")?;
    let format = range.sample_format();
    let buffer = *range.buffer_size();
    let mut config = range
        .with_sample_rate(cpal::SampleRate(RATE as u32))
        .config();
    if let cpal::SupportedBufferSize::Range { min, max } = buffer {
        config.buffer_size = cpal::BufferSize::Fixed(256u32.clamp(min, max));
    }
    stream(&device, &config, format, consumer, dropped)
}
pub fn receive(
    connection: &mut (impl Read + Write),
    output: &str,
    dropped: Arc<AtomicU64>,
) -> Result<()> {
    let (mut producer, consumer): (Producer<f32>, _) = RingBuffer::new(RATE / 5);
    #[cfg(windows)]
    let playback =
        crate::windows_audio::Playback::start(output, Playout::new(consumer, dropped.clone()))?;
    #[cfg(not(windows))]
    let playback = portable_stream(output, consumer, dropped.clone())?;
    let mut hello = [0; 11];
    connection.read_exact(&mut hello)?;
    if &hello != b"AirRemoteA1" {
        bail!("Invalid audio handshake");
    }
    connection.write_all(b"AirRemoteA2")?;
    connection.flush()?;
    #[cfg(not(windows))]
    playback.play()?;
    loop {
        #[cfg(windows)]
        playback.check()?;
        let mut length = [0; 4];
        connection.read_exact(&mut length)?;
        let length = u32::from_be_bytes(length) as usize;
        if !(1..=16_384).contains(&length) {
            bail!("Audio frame too large");
        }
        let mut packet = vec![0; length];
        connection.read_exact(&mut packet)?;
        for sample in decode(&packet)? {
            if producer.push(sample).is_err() {
                dropped.fetch_add(1, Ordering::Relaxed);
            }
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn packet(bytes: Vec<u8>, format: u32) -> Vec<u8> {
        Packet {
            buffer: bytes,
            sample_rate: 48000,
            channel_count: 1,
            audio_format: format,
        }
        .encode_to_vec()
    }
    #[test]
    fn validates_audio_boundaries() {
        assert!(decode(&packet(vec![0], 2)).is_err());
        assert!(decode(&packet(f32::NAN.to_le_bytes().to_vec(), 4)).is_err());
        assert!(decode(&packet(vec![0; 9604], 4)).is_err());
        assert_eq!(decode(&packet(vec![0, 128], 2)).unwrap(), vec![-1.0]);
    }
    #[test]
    fn waits_for_jitter_cushion() {
        let (p, c) = RingBuffer::new(9600);
        let mut s = Playout {
            input: c,
            started: false,
            current: 0.,
            next: 0.,
            phase: 0.,
            dropped: Arc::new(AtomicU64::new(0)),
        };
        let mut out = [1f32; 256];
        s.render(&mut out, 2);
        assert!(out.iter().all(|x| *x == 0.));
        drop(p);
    }
    #[test]
    fn starts_after_one_packet_and_recovers_from_underflow() {
        let (mut producer, consumer) = RingBuffer::new(9600);
        let mut player = Playout::new(consumer, Arc::new(AtomicU64::new(0)));
        for _ in 0..480 {
            producer.push(0.25).unwrap();
        }
        let mut out = [0f32; 256];
        player.render(&mut out, 2);
        assert!(out.iter().all(|v| (*v - 0.25).abs() < 0.001));
        for _ in 0..10 {
            player.render(&mut out, 2);
        }
        assert!(out.iter().all(|v| *v == 0.0));
        for _ in 0..480 {
            producer.push(-0.25).unwrap();
        }
        player.render(&mut out, 2);
        assert!(out.iter().all(|v| (*v + 0.25).abs() < 0.001));
    }
    #[test]
    fn discards_accumulated_old_audio() {
        let (mut p, c) = RingBuffer::new(9600);
        for _ in 0..4000 {
            p.push(0.5).unwrap();
        }
        let dropped = Arc::new(AtomicU64::new(0));
        let mut s = Playout {
            input: c,
            started: false,
            current: 0.,
            next: 0.,
            phase: 0.,
            dropped: dropped.clone(),
        };
        let mut out = [0f32; 256];
        s.render(&mut out, 2);
        assert!(dropped.load(Ordering::Relaxed) > 3000);
        assert!(out.iter().all(|x| (*x - 0.5).abs() < 0.001));
    }
}
