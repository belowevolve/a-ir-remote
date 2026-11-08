//! In-memory acoustic round-trip diagnostic. Start the phone's microphone stream first.
//! Emits four quiet chirps at the current physical default output; never changes volume.
//! Measures output-loopback QPC -> CABLE Output QPC, including the acoustic/phone path.
//! Driver timestamps do not establish write-to-capture availability latency.
//! Correlated offsets must not be reported as calibrated end-to-end latency.
//! --cable-only measures CABLE Input loopback -> CABLE Output, excluding phone/network.

#[cfg(not(windows))]
fn main() {
    eprintln!("This diagnostic requires Windows WASAPI.");
}

#[cfg(windows)]
fn main() -> anyhow::Result<()> {
    windows_probe::run()
}

#[cfg(any(windows, test))]
mod signal {
    pub const RATE: usize = 8_000;
    pub const CHIRP_SECONDS: f64 = 0.12;

    pub fn chirp(t: f64) -> f32 {
        if !(0.0..CHIRP_SECONDS).contains(&t) {
            return 0.0;
        }
        let envelope = (std::f64::consts::PI * t / CHIRP_SECONDS).sin().powi(2);
        let phase = std::f64::consts::TAU * (600.0 * t + 0.5 * 2_000.0 / CHIRP_SECONDS * t * t);
        (0.10 * envelope * phase.sin()) as f32
    }

    pub struct Timeline {
        pub samples: Vec<f32>,
        pub valid: Vec<bool>,
        pub clock_segments: Vec<ClockSegment>,
    }

    pub struct Packet {
        pub qpc: u64,
        pub index: u64,
        pub data: Vec<f32>,
        pub valid: bool,
    }

    pub struct ClockSegment {
        pub first_packet: usize,
        pub packet_count: usize,
        pub anchor_ms: f64,
        pub max_residual_ms: f64,
        pub p95_residual_ms: f64,
        pub accepted: bool,
    }

    pub fn timeline(
        packets: &[Packet],
        origin: u64,
        length: usize,
        device_rate: usize,
    ) -> Timeline {
        let mut grid = Timeline {
            samples: vec![0.0; length],
            valid: vec![false; length],
            clock_segments: Vec::new(),
        };
        let mut boundaries = Vec::new();
        let mut first = 0;
        while first < packets.len() {
            if !packets[first].valid || packets[first].data.is_empty() {
                first += 1;
                continue;
            }
            let mut end = first + 1;
            while end < packets.len()
                && packets[end].valid
                && !packets[end].data.is_empty()
                && packets[end].index == packets[end - 1].index + packets[end - 1].data.len() as u64
            {
                end += 1;
            }
            let start_index = packets[first].index;
            // Device indexes prove sample continuity. Packet QPC observations can jitter by
            // ~1ms even without TIMESTAMP_ERROR. Fit one offset per uninterrupted segment;
            // never warp individual packets, bridge missing frames, or accept >2ms residual.
            let mut anchors: Vec<_> = packets[first..end]
                .iter()
                .map(|packet| {
                    (packet.qpc as f64 - origin as f64) / 10_000.0
                        - (packet.index - start_index) as f64 * 1000.0 / device_rate as f64
                })
                .collect();
            anchors.sort_by(f64::total_cmp);
            let anchor_ms = (anchors[(anchors.len() - 1) / 2] + anchors[anchors.len() / 2]) / 2.0;
            let mut residuals: Vec<_> = anchors.iter().map(|x| (x - anchor_ms).abs()).collect();
            residuals.sort_by(f64::total_cmp);
            let max_residual_ms = *residuals.last().unwrap();
            let p95_residual_ms = residuals
                [((residuals.len() as f64 * 0.95).ceil() as usize - 1).min(residuals.len() - 1)];
            let accepted = max_residual_ms <= 2.0;
            grid.clock_segments.push(ClockSegment {
                first_packet: first,
                packet_count: end - first,
                anchor_ms,
                max_residual_ms,
                p95_residual_ms,
                accepted,
            });
            if accepted {
                // Even a one-device-frame gap smaller than the analysis grid must prevent
                // matching across independently anchored segments.
                if grid.clock_segments.len() > 1 {
                    boundaries.push((anchor_ms * RATE as f64 / 1000.0).ceil().max(0.0) as usize);
                }
                for number in first..end {
                    let packet = &packets[number];
                    let offset = anchor_ms * RATE as f64 / 1000.0
                        + (packet.index - start_index) as f64 * RATE as f64 / device_rate as f64;
                    let until = anchor_ms * RATE as f64 / 1000.0
                        + (packet.index - start_index + packet.data.len() as u64) as f64
                            * RATE as f64
                            / device_rate as f64;
                    for i in (offset.ceil().max(0.0) as usize)
                        ..(until.ceil().max(0.0) as usize).min(length)
                    {
                        let p = (i as f64 - offset) * device_rate as f64 / RATE as f64;
                        let a = (p.floor() as usize).min(packet.data.len() - 1);
                        let b = packet
                            .data
                            .get(a + 1)
                            .copied()
                            .or_else(|| {
                                if number + 1 < end {
                                    packets[number + 1].data.first().copied()
                                } else {
                                    None
                                }
                            })
                            .unwrap_or(packet.data[a]);
                        grid.samples[i] = packet.data[a] + (b - packet.data[a]) * p.fract() as f32;
                        grid.valid[i] = grid.samples[i].is_finite();
                    }
                }
            }
            first = end;
        }
        for boundary in boundaries {
            if let Some(valid) = grid.valid.get_mut(boundary) {
                *valid = false;
            }
        }
        grid
    }

    #[derive(Debug)]
    pub struct Match {
        pub index: usize,
        pub correlation: f64,
        pub alternative: f64,
        pub rms: f64,
    }

    impl Match {
        pub fn confident(&self, minimum_rms: f64) -> bool {
            self.correlation >= 0.25
                && self.rms >= minimum_rms
                && self.correlation >= self.alternative * 1.25
                && self.correlation - self.alternative >= 0.05
        }
    }

    /// Zero-mean normalized matched filter. Invalid timestamp windows are never candidates.
    pub fn find(reference: &[f32], input: &Timeline, start: usize, end: usize) -> Option<Match> {
        let n = reference.len();
        if n == 0 || input.samples.len() < n || start > end {
            return None;
        }
        let end = end.min(input.samples.len() - n);
        if start > end {
            return None;
        }
        let mean = reference.iter().map(|&x| x as f64).sum::<f64>() / n as f64;
        let reference: Vec<f64> = reference.iter().map(|&x| x as f64 - mean).collect();
        let energy = reference.iter().map(|x| x * x).sum::<f64>();
        if energy < 1e-12 {
            return None;
        }
        let mut sums = vec![0.0; input.samples.len() + 1];
        let mut squares = sums.clone();
        let mut invalid = vec![0u32; sums.len()];
        for (i, &sample) in input.samples.iter().enumerate() {
            let value = if sample.is_finite() {
                sample as f64
            } else {
                0.0
            };
            sums[i + 1] = sums[i] + value;
            squares[i + 1] = squares[i] + value * value;
            invalid[i + 1] = invalid[i] + u32::from(!input.valid[i] || !sample.is_finite());
        }
        let score = |at: usize| -> Option<(f64, f64)> {
            if invalid[at + n] != invalid[at] {
                return None;
            }
            let sum = sums[at + n] - sums[at];
            let square = squares[at + n] - squares[at];
            let centered = (square - sum * sum / n as f64).max(0.0);
            if centered < 1e-12 {
                return None;
            }
            let dot = reference
                .iter()
                .zip(&input.samples[at..at + n])
                .map(|(r, &s)| r * s as f64)
                .sum::<f64>();
            Some((
                (dot / (energy * centered).sqrt()).abs().min(1.0),
                (square / n as f64).sqrt(),
            ))
        };
        // Search every lag: an oscillatory chirp can have its strongest peak between coarse steps.
        let candidates: Vec<_> = (start..=end)
            .filter_map(|i| score(i).map(|(c, rms)| (i, c, rms)))
            .collect();
        let &(index, correlation, rms) = candidates.iter().max_by(|a, b| a.1.total_cmp(&b.1))?;
        // Exclude the same correlation peak and nearby acoustic reflections (20 ms).
        let alternative = candidates
            .iter()
            .filter(|(i, _, _)| i.abs_diff(index) > RATE / 50)
            .map(|(_, c, _)| *c)
            .fold(0.0, f64::max);
        Some(Match {
            index,
            correlation,
            alternative,
            rms,
        })
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        #[test]
        fn detects_delayed_scaled_inverted_signal_and_rejects_ambiguity() {
            let reference: Vec<_> = (0..960).map(|i| chirp(i as f64 / RATE as f64)).collect();
            let mut input = Timeline {
                samples: vec![0.0; RATE],
                valid: vec![true; RATE],
                clock_segments: Vec::new(),
            };
            for (i, &value) in reference.iter().enumerate() {
                input.samples[i + 1235] = -value * 0.04;
            }
            let found = find(&reference, &input, 0, 6000).unwrap();
            assert_eq!(found.index, 1235);
            assert!(found.confident(1e-5));
            for (i, &value) in reference.iter().enumerate() {
                input.samples[i + 4235] = value * 0.04;
            }
            assert!(!find(&reference, &input, 0, 6000).unwrap().confident(1e-5));
        }
        #[test]
        fn rejects_silence_and_corrupted_timestamps() {
            let reference: Vec<_> = (0..960).map(|i| chirp(i as f64 / RATE as f64)).collect();
            let mut input = Timeline {
                samples: vec![0.0; 2000],
                valid: vec![true; 2000],
                clock_segments: Vec::new(),
            };
            assert!(find(&reference, &input, 0, 1000).is_none());
            input.samples[100..1060].copy_from_slice(&reference);
            input.valid[100..1060].fill(false);
            assert!(find(&reference, &input, 0, 1000).is_none());
        }
        #[test]
        fn contiguous_packets_do_not_gain_gaps_from_100ns_timestamp_rounding() {
            let packets: Vec<_> = (0..3)
                .map(|i| Packet {
                    qpc: 100_000_000 + i * 100_000 + u64::from(i > 0),
                    index: i * 480,
                    data: vec![0.5; 480],
                    valid: true,
                })
                .collect();
            let grid = timeline(&packets, 100_000_000, 240, 48_000);
            assert!(grid.valid[1..].iter().all(|&valid| valid));
            assert!(grid.samples[1..].iter().all(|&value| value == 0.5));
        }
        #[test]
        fn missing_device_frames_and_large_clock_jumps_are_not_bridged() {
            let mut packets: Vec<_> = (0..2)
                .map(|i| Packet {
                    qpc: 100_000_000 + i * 110_000,
                    index: i * 528,
                    data: vec![0.5; 480],
                    valid: true,
                })
                .collect();
            let grid = timeline(&packets, 100_000_000, 168, 48_000);
            assert!(grid.valid[80..88].iter().all(|&valid| !valid));
            packets[1].index = 480;
            packets[1].qpc = 100_150_000; // 5ms clock jump => >2ms anchor residual.
            let grid = timeline(&packets, 100_000_000, 168, 48_000);
            assert!(grid.valid.iter().all(|&valid| !valid));
        }

        #[test]
        fn half_ms_qpc_jitter_keeps_index_continuity_and_chirp_match() {
            let mut packets: Vec<_> = (0..100u64)
                .map(|packet| Packet {
                    qpc: (100_000_000i64
                        + packet as i64 * 100_000
                        + if packet % 2 == 0 { 5000 } else { -5000 })
                        as u64,
                    index: packet * 480,
                    data: (0..480)
                        .map(|i| chirp((packet * 480 + i) as f64 / 48_000.0 - 0.2))
                        .collect(),
                    valid: true,
                })
                .collect();
            let grid = timeline(&packets, 100_000_000, RATE, 48_000);
            assert!(grid.valid.iter().all(|&valid| valid));
            assert_eq!(grid.clock_segments.len(), 1);
            assert!((grid.clock_segments[0].max_residual_ms - 0.5).abs() < 1e-9);
            let reference: Vec<_> = (0..960).map(|i| chirp(i as f64 / RATE as f64)).collect();
            let matched = find(&reference, &grid, 0, 5000).unwrap();
            assert_eq!(matched.index, 1600);
            assert!(matched.confident(0.001));

            // A driver TIMESTAMP_ERROR/discontinuity is represented by valid=false.
            packets[20].valid = false;
            let damaged = timeline(&packets, 100_000_000, RATE, 48_000);
            assert_eq!(damaged.clock_segments.len(), 2);
            assert!(damaged.valid[1610..1670].iter().all(|&valid| !valid));
        }
        #[test]
        fn sub_grid_index_gap_still_blocks_cross_segment_matching() {
            let packets = vec![
                Packet {
                    qpc: 100_000_000,
                    index: 0,
                    data: vec![0.5; 480],
                    valid: true,
                },
                Packet {
                    qpc: 100_100_000,
                    index: 481,
                    data: vec![0.5; 480],
                    valid: true,
                },
            ];
            let grid = timeline(&packets, 100_000_000, 160, 48_000);
            assert_eq!(grid.clock_segments.len(), 2);
            assert!(!grid.valid[80]);
        }
    }
}

#[cfg(windows)]
mod windows_probe {
    use super::signal::{self, Packet, Timeline, RATE};
    use anyhow::{bail, ensure, Context, Result};
    use serde_json::{json, Value};
    use std::{
        thread,
        time::{Duration, Instant},
    };
    use wasapi::{
        AudioClient, BufferInfo, Device, DeviceEnumerator, Direction, SampleType, StreamMode,
        WaveFormat,
    };
    use windows::Win32::Media::Audio::IAudioCaptureClient;

    const DEVICE_RATE: usize = 48_000;
    const SECONDS: usize = 11;
    const TRIALS: usize = 4;
    struct Capture {
        client: AudioClient,
        capture: IAudioCaptureClient,
        channels: usize,
        packets: Vec<Packet>,
        frames: usize,
        timestamp_errors: usize,
        discontinuities: usize,
    }
    impl Capture {
        fn open(device: &Device) -> Result<Self> {
            let mut client = device.get_iaudioclient()?;
            let mix = client.get_mixformat()?;
            let channels = mix.get_nchannels() as usize;
            ensure!(channels > 0 && channels <= 32, "Unsupported channel count");
            let format = WaveFormat::new(
                32,
                32,
                &SampleType::Float,
                DEVICE_RATE,
                channels,
                Some(mix.get_dwchannelmask()),
            );
            // A render endpoint initialized as Capture is WASAPI render-loopback.
            client.initialize_client(
                &format,
                &Direction::Capture,
                &StreamMode::PollingShared {
                    autoconvert: true,
                    buffer_duration_hns: 1_000_000,
                },
            )?;
            // Read raw to honor SILENT (the driver may supply a null sample pointer).
            let capture = unsafe {
                client
                    .as_iaudioclient()
                    .GetService::<IAudioCaptureClient>()?
            };
            client.start_stream()?;
            Ok(Self {
                client,
                capture,
                channels,
                packets: Vec::new(),
                frames: 0,
                timestamp_errors: 0,
                discontinuities: 0,
            })
        }
        fn drain(&mut self) -> Result<()> {
            while unsafe { self.capture.GetNextPacketSize()? } > 0 {
                let mut data = std::ptr::null_mut();
                let (mut frames, mut flags, mut position, mut qpc) = (0, 0, 0, 0);
                unsafe {
                    self.capture.GetBuffer(
                        &mut data,
                        &mut frames,
                        &mut flags,
                        Some(&mut position),
                        Some(&mut qpc),
                    )?;
                }
                let info = BufferInfo::new(flags, position, qpc);
                let result = (|| -> Result<Packet> {
                    ensure!(
                        self.frames + frames as usize <= DEVICE_RATE * 30,
                        "Capture exceeded 30-second memory bound"
                    );
                    let mut mono = vec![0.0; frames as usize];
                    if !info.flags.silent && frames > 0 {
                        ensure!(!data.is_null(), "Non-silent capture supplied no samples");
                        // WASAPI guarantees buffer validity until ReleaseBuffer. Read bytewise to avoid alignment assumptions.
                        let bytes = unsafe {
                            std::slice::from_raw_parts(data, frames as usize * self.channels * 4)
                        };
                        for (out, frame) in
                            mono.iter_mut().zip(bytes.chunks_exact(self.channels * 4))
                        {
                            *out = frame
                                .as_chunks::<4>()
                                .0
                                .iter()
                                .map(|x| f32::from_le_bytes(*x))
                                .sum::<f32>()
                                / self.channels as f32;
                        }
                    }
                    Ok(Packet {
                        qpc: info.timestamp,
                        index: info.index,
                        data: mono,
                        valid: info.timestamp > 0
                            && !info.flags.timestamp_error
                            && !info.flags.data_discontinuity,
                    })
                })();
                unsafe {
                    self.capture.ReleaseBuffer(frames)?;
                }
                self.timestamp_errors += usize::from(info.flags.timestamp_error);
                self.discontinuities += usize::from(info.flags.data_discontinuity);
                self.frames += frames as usize;
                self.packets.push(result?);
            }
            Ok(())
        }
        fn timeline(&self, origin: u64) -> Timeline {
            signal::timeline(&self.packets, origin, (SECONDS + 2) * RATE, DEVICE_RATE)
        }
        fn diagnostics(&self, origin: u64) -> Value {
            let mut clock_errors = Vec::new();
            let mut index_gaps = 0;
            let mut nonmonotonic_qpc = 0;
            let mut contiguous = 0;
            for pair in self.packets.windows(2) {
                let (a, b) = (&pair[0], &pair[1]);
                if !a.valid || !b.valid {
                    continue;
                }
                if b.index == a.index + a.data.len() as u64 {
                    contiguous += 1;
                } else {
                    index_gaps += 1;
                }
                if b.qpc <= a.qpc {
                    nonmonotonic_qpc += 1;
                }
                if b.index >= a.index && b.qpc > a.qpc {
                    clock_errors.push(
                        (b.qpc - a.qpc) as f64 / 10_000.0
                            - (b.index - a.index) as f64 * 1000.0 / DEVICE_RATE as f64,
                    );
                }
            }
            clock_errors.sort_by(f64::total_cmp);
            let mut samples = 0usize;
            let mut energy = 0.0;
            let mut peak = 0.0f32;
            let mut nonfinite = 0usize;
            for &sample in self.packets.iter().flat_map(|p| &p.data) {
                if sample.is_finite() {
                    samples += 1;
                    energy += (sample as f64).powi(2);
                    peak = peak.max(sample.abs());
                } else {
                    nonfinite += 1;
                }
            }
            let summary = |number: usize, packet: &Packet| {
                let previous = number.checked_sub(1).map(|i| &self.packets[i]);
                let qpc_delta_ms = previous.map(|p| (packet.qpc as f64 - p.qpc as f64) / 10_000.0);
                let index_delta_frames = previous.map(|p| packet.index as i128 - p.index as i128);
                let rms = (packet
                    .data
                    .iter()
                    .filter(|x| x.is_finite())
                    .map(|&x| (x as f64).powi(2))
                    .sum::<f64>()
                    / packet.data.len().max(1) as f64)
                    .sqrt();
                json!({"packet":number,"qpc_from_origin_ms":(packet.qpc as f64-origin as f64)/10_000.0,
                    "index":packet.index,"frames":packet.data.len(),"valid":packet.valid,"rms":rms,
                    "qpc_delta_ms":qpc_delta_ms,"index_delta_frames":index_delta_frames})
            };
            let representative: Vec<_> = self
                .packets
                .iter()
                .enumerate()
                .filter(|(i, _)| *i < 8 || *i % 100 == 0)
                .take(40)
                .map(|(i, p)| summary(i, p))
                .collect();
            let signal_packets: Vec<_> = self
                .packets
                .iter()
                .enumerate()
                .filter(|(_, p)| p.data.iter().any(|x| x.is_finite() && x.abs() > 0.001))
                .take(16)
                .map(|(i, p)| summary(i, p))
                .collect();
            let anomalies: Vec<_> = self
                .packets
                .windows(2)
                .enumerate()
                .filter(|(_, pair)| {
                    let (a, b) = (&pair[0], &pair[1]);
                    b.index != a.index + a.data.len() as u64
                        || b.qpc <= a.qpc
                        || ((b.qpc as f64 - a.qpc as f64) / 10_000.0 - a.data.len() as f64 / 48.0)
                            .abs()
                            > 1.0 / 48.0
                })
                .take(24)
                .map(|(i, pair)| summary(i + 1, &pair[1]))
                .collect();
            json!({"packets":self.packets.len(),"frames":self.frames,"channels":self.channels,
                "raw_rms":(energy/samples.max(1) as f64).sqrt(),"raw_peak":peak,"nonfinite_samples":nonfinite,
                "valid_packets":self.packets.iter().filter(|p| p.valid).count(),
                "contiguous_index_edges":contiguous,"index_gap_edges":index_gaps,"nonmonotonic_qpc_edges":nonmonotonic_qpc,
                "qpc_minus_index_step_ms_min":clock_errors.first(),"qpc_minus_index_step_ms_max":clock_errors.last(),
                "qpc_minus_index_step_ms_median":clock_errors.get(clock_errors.len()/2),
                "representative_packets":representative,"first_signal_packets":signal_packets,"first_timing_anomalies":anomalies})
        }
    }
    impl Drop for Capture {
        fn drop(&mut self) {
            let _ = self.client.stop_stream();
        }
    }

    fn window_diagnostics(grid: &Timeline, start: usize, end: usize) -> Value {
        let start = start.min(grid.samples.len());
        let end = end.min(grid.samples.len()).max(start);
        let data = &grid.samples[start..end];
        let mut longest = 0usize;
        let mut run = 0usize;
        for &valid in &grid.valid[start..end] {
            run = if valid { run + 1 } else { 0 };
            longest = longest.max(run);
        }
        json!({"samples":data.len(),"valid_samples":grid.valid[start..end].iter().filter(|&&v| v).count(),
            "longest_valid_run_ms":longest as f64*1000.0/RATE as f64,
            "rms":(data.iter().map(|&x| (x as f64).powi(2)).sum::<f64>() / data.len().max(1) as f64).sqrt(),
            "peak":data.iter().fold(0.0f32,|peak,x| peak.max(x.abs()))})
    }

    fn accepted_clock_residual(grid: &Timeline) -> Option<f64> {
        grid.clock_segments
            .iter()
            .filter(|segment| segment.accepted)
            .map(|segment| segment.max_residual_ms)
            .reduce(f64::max)
    }

    fn clock_diagnostics(grid: &Timeline) -> Value {
        json!({"method":"nominal device sample rate with median QPC offset per contiguous valid segment",
            "reject_max_residual_above_ms":2.0,
            "accepted_max_anchor_residual_ms":accepted_clock_residual(grid),
            "accepted_p95_anchor_residual_ms":grid.clock_segments.iter().filter(|segment| segment.accepted)
                .map(|segment| segment.p95_residual_ms).reduce(f64::max),
            "segments":grid.clock_segments.iter().map(|segment| json!({
                "first_packet":segment.first_packet,"packets":segment.packet_count,"anchor_from_origin_ms":segment.anchor_ms,
                "max_anchor_residual_ms":segment.max_residual_ms,"p95_anchor_residual_ms":segment.p95_residual_ms,
                "accepted":segment.accepted})).collect::<Vec<_>>()})
    }

    pub fn run() -> Result<()> {
        wasapi::initialize_mta().ok()?;
        struct Com;
        impl Drop for Com {
            fn drop(&mut self) {
                wasapi::deinitialize();
            }
        }
        let _com = Com;
        let enumerator = DeviceEnumerator::new()?;
        let cable_only = std::env::args().any(|a| a == "--cable-only");
        if std::env::args().any(|a| a == "--list" || a == "--describe") {
            let mut devices = Vec::new();
            for direction in [Direction::Render, Direction::Capture] {
                for device in &enumerator.get_device_collection(&direction)? {
                    let device = device?;
                    let mix = device.get_iaudioclient()?.get_mixformat()?;
                    devices.push(json!({"direction":format!("{direction:?}"),"name":device.get_friendlyname()?,
                        "id":device.get_id()?,"mix_rate":mix.get_samplespersec(),"channels":mix.get_nchannels()}));
                }
            }
            println!(
                "{}",
                json!({"devices":devices,"capture_seconds":SECONDS,"trials":TRIALS,
                "method":"WASAPI loopback -> CABLE Output, QPC timestamps; phone streaming required; no audio saved"})
            );
            return Ok(());
        }
        let output = if cable_only {
            let mut devices: Vec<_> = (&enumerator.get_device_collection(&Direction::Render)?)
                .into_iter()
                .filter_map(|device| device.ok())
                .filter(|device| {
                    device
                        .get_friendlyname()
                        .unwrap_or_default()
                        .contains("CABLE Input")
                })
                .collect();
            ensure!(
                devices.len() == 1,
                "Expected one CABLE Input render endpoint, found {}",
                devices.len()
            );
            devices.remove(0)
        } else {
            enumerator.get_default_device(&Direction::Render)?
        };
        let output_name = output.get_friendlyname()?;
        let lower = output_name.to_lowercase();
        ensure!(
            cable_only
                || ![
                    "cable",
                    "virtual",
                    "voicemeeter",
                    "vb-audio",
                    "sonar",
                    "remote",
                    "steam",
                    "ndi"
                ]
                .iter()
                .any(|s| lower.contains(s)),
            "Default output is virtual ({output_name}); select physical speakers in Windows first"
        );
        let cables: Vec<_> = (&enumerator.get_device_collection(&Direction::Capture)?)
            .into_iter()
            .filter_map(|d| d.ok())
            .filter(|d| {
                d.get_friendlyname()
                    .unwrap_or_default()
                    .contains("CABLE Output")
            })
            .collect();
        ensure!(
            cables.len() == 1,
            "Expected one CABLE Output input, found {}",
            cables.len()
        );
        let cable = &cables[0];
        let mut playback = output.get_iaudioclient()?;
        let mix = playback.get_mixformat()?;
        let channels = mix.get_nchannels() as usize;
        ensure!(
            channels > 0 && channels <= 32,
            "Unsupported render channels"
        );
        let format = WaveFormat::new(
            32,
            32,
            &SampleType::Float,
            DEVICE_RATE,
            channels,
            Some(mix.get_dwchannelmask()),
        );
        playback.initialize_client(
            &format,
            &Direction::Render,
            &StreamMode::PollingShared {
                autoconvert: true,
                buffer_duration_hns: 1_000_000,
            },
        )?;
        let render = playback.get_audiorenderclient()?;
        let capacity = playback.get_buffer_size()? as usize;
        let mut bytes = vec![0u8; capacity * channels * 4];
        render.write_to_device(capacity, &bytes, None)?;
        let mut position = capacity;
        let mut source = Capture::open(&output).context("Physical output loopback")?;
        let mut received = Capture::open(cable).context("CABLE Output capture")?;
        if cable_only {
            eprintln!("Virtual-cable-only probe: four chirps into {output_name}. Stop phone streaming first. No physical playback or volume change.");
        } else {
            eprintln!("Four 120-ms chirps on {output_name}; keep phone near speakers and microphone streaming. No volume change.");
        }
        playback.start_stream()?;
        let started = Instant::now();
        let result = (|| -> Result<()> {
            while started.elapsed() < Duration::from_secs(SECONDS as u64) {
                source.drain()?;
                received.drain()?;
                let frames = playback.get_available_space_in_frames()? as usize;
                ensure!(frames <= capacity, "Render capacity changed");
                for (i, frame) in bytes[..frames * channels * 4]
                    .chunks_exact_mut(channels * 4)
                    .enumerate()
                {
                    let seconds = (position + i) as f64 / DEVICE_RATE as f64;
                    let value = (0..TRIALS)
                        .map(|trial| signal::chirp(seconds - (2 + trial * 2) as f64))
                        .sum::<f32>();
                    for sample in frame.as_chunks_mut::<4>().0 {
                        sample.copy_from_slice(&value.to_le_bytes());
                    }
                }
                if frames > 0 {
                    render.write_to_device(frames, &bytes[..frames * channels * 4], None)?;
                    position += frames;
                }
                thread::sleep(Duration::from_millis(2));
            }
            source.drain()?;
            received.drain()?;
            Ok(())
        })();
        let _ = playback.stop_stream();
        result?;
        let origin = source
            .packets
            .iter()
            .chain(&received.packets)
            .filter(|p| p.valid)
            .map(|p| p.qpc)
            .min()
            .context("No valid QPC timestamps")?;
        let first_source = source
            .packets
            .iter()
            .find(|p| p.valid)
            .context("No loopback packets")?
            .qpc;
        let source_grid = source.timeline(origin);
        let received_grid = received.timeline(origin);
        let timestamp_uncertainty = accepted_clock_residual(&source_grid)
            .zip(accepted_clock_residual(&received_grid))
            .map(|(source, receiver)| source + receiver + 1000.0 / RATE as f64);
        let reference: Vec<_> = (0..(signal::CHIRP_SECONDS * RATE as f64) as usize)
            .map(|i| signal::chirp(i as f64 / RATE as f64))
            .collect();
        let noise_samples = received_grid.samples[RATE / 2..RATE * 3 / 2].iter();
        let noise_rms =
            (noise_samples.map(|x| (*x as f64).powi(2)).sum::<f64>() / RATE as f64).sqrt();
        let mut trials = Vec::<Value>::new();
        let mut latencies = Vec::<f64>::new();
        for trial in 0..TRIALS {
            let expected = ((first_source - origin) as f64 * RATE as f64 / 10_000_000.0) as usize
                + (2 + trial * 2) * RATE;
            let window_start = expected
                .saturating_sub(RATE / 2)
                .min(source_grid.samples.len());
            let window_end = (expected + RATE / 2 + reference.len()).min(source_grid.samples.len());
            let window = &source_grid.samples[window_start..window_end];
            let mut result = json!({"trial":trial+1,"accepted":false,"source_correlation":null,"source_rms":null,
                "source_window_rms":(window.iter().map(|&x| (x as f64).powi(2)).sum::<f64>() / window.len().max(1) as f64).sqrt(),
                "source_window_samples":window.len(),"source_valid_samples":source_grid.valid[window_start..window_end].iter().filter(|&&v| v).count()});
            result["receiver_window"] = window_diagnostics(
                &received_grid,
                expected.saturating_sub(RATE / 2),
                expected + RATE * 3 / 2 + reference.len(),
            );
            let Some(found_source) = signal::find(
                &reference,
                &source_grid,
                expected.saturating_sub(RATE / 2),
                expected + RATE / 2,
            ) else {
                result["reason"] = json!("source_missing_or_invalid_timestamps");
                trials.push(result);
                continue;
            };
            result["source_correlation"] = json!(found_source.correlation);
            result["source_alternative_correlation"] = json!(found_source.alternative);
            result["source_rms"] = json!(found_source.rms);
            if !found_source.confident(0.0001) {
                result["reason"] = json!("source_not_confident");
                trials.push(result);
                continue;
            }
            let actual_source =
                &source_grid.samples[found_source.index..found_source.index + reference.len()];
            let Some(found) = signal::find(
                actual_source,
                &received_grid,
                found_source.index.saturating_sub(RATE / 10),
                found_source.index + RATE * 3 / 2,
            ) else {
                result["reason"] = json!("received_missing_silent_or_invalid_timestamps");
                trials.push(result);
                continue;
            };
            let latency = (found.index as f64 - found_source.index as f64) * 1000.0 / RATE as f64;
            let confident = found.confident((noise_rms * 1.5).max(0.00003));
            let accepted =
                confident && ((if cable_only { 0.0 } else { 2.0 })..=1500.0).contains(&latency);
            if accepted {
                latencies.push(latency);
            }
            let received_result = json!({"accepted":accepted,
                "latency_ms":if accepted {Some(latency)} else {None},"candidate_lag_ms":latency,
                "correlation":found.correlation,
                "alternative_correlation":found.alternative,"received_rms":found.rms,
                "reason":if accepted {"matched"} else if !confident {"weak_ambiguous_or_gated"} else if cable_only {"lag_out_of_range"} else {"negative_or_direct_route_suspected"}});
            result
                .as_object_mut()
                .unwrap()
                .extend(received_result.as_object().unwrap().clone());
            trials.push(result);
        }
        latencies.sort_by(f64::total_cmp);
        let median = if latencies.is_empty() {
            None
        } else {
            Some((latencies[(latencies.len() - 1) / 2] + latencies[latencies.len() / 2]) / 2.0)
        };
        println!(
            "{}",
            json!({"mode":if cable_only {"virtual_cable_only"} else {"acoustic_round_trip"},"source_device":output_name,"source_endpoint_id":output.get_id()?,
            "receiver_device":cable.get_friendlyname()?,"receiver_endpoint_id":cable.get_id()?,
            "method":if cable_only {"CABLE Input render-loopback QPC to CABLE Output capture QPC; virtual cable path only, excludes phone/network/companion receiver and physical acoustics"} else {"Physical render-loopback QPC to CABLE Output capture QPC; includes speaker/acoustic/phone/network/receiver/CABLE delay, not pure network latency"},
            "capture_seconds":SECONDS,"chirp_peak_amplitude":0.10,"analysis_sample_rate":RATE,"timestamp_grid_ms":1000.0/RATE as f64,
            "absolute_latency_calibrated":false,
            "reported_values":"Uncalibrated endpoint timestamp offsets; positive matches alone do not establish physical or capture-callback availability latency",
            "noise_rms":noise_rms,"accepted":latencies.len(),"rejected":TRIALS-latencies.len(),"median_ms":median,
            "min_ms":latencies.first(),"max_ms":latencies.last(),"trials":trials,
            "source_timestamp_errors":source.timestamp_errors,"receiver_timestamp_errors":received.timestamp_errors,
            "source_discontinuities":source.discontinuities,"receiver_discontinuities":received.discontinuities,
            "source_timing":source.diagnostics(origin),"receiver_timing":received.diagnostics(origin),
            "source_clock_fit":clock_diagnostics(&source_grid),"receiver_clock_fit":clock_diagnostics(&received_grid),
            "observed_timestamp_uncertainty_envelope_ms":timestamp_uncertainty,
            "uncertainty_note":"Sum of observed maximum anchor residuals plus analysis grid; empirical timing envelope, not calibrated absolute accuracy. Unknown fixed device timestamp biases are excluded."})
        );
        if latencies.is_empty() {
            if cable_only {
                bail!("No confident virtual-cable matches; check CABLE endpoint activity and capture timestamps");
            }
            bail!("No confident acoustic matches; check physical speakers, phone streaming, mute and speech processing");
        }
        Ok(())
    }
}
