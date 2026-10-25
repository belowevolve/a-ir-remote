//! Diagnostic only: measures local receiver -> CABLE Output, without a phone or HDMI playback.
use anyhow::{bail, Context, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use std::{
    io::{Read, Write},
    net::TcpStream,
    sync::{Arc, Mutex},
    thread,
    time::{Duration, Instant},
};
trait Connection: Read + Write {}
impl<T: Read + Write> Connection for T {}
fn main() -> Result<()> {
    let host = std::env::args()
        .find(|a| a.starts_with("--host="))
        .map(|a| a[7..].to_owned())
        .unwrap_or("127.0.0.1".into());
    let device = cpal::default_host()
        .input_devices()?
        .find(|d| d.name().unwrap_or_default().contains("CABLE Output"))
        .context("CABLE Output missing")?;
    let range = device
        .supported_input_configs()?
        .find(|c| {
            c.sample_format() == cpal::SampleFormat::F32
                && c.min_sample_rate().0 <= 48000
                && c.max_sample_rate().0 >= 48000
        })
        .context("48 kHz Float32 capture missing")?;
    let buffer = *range.buffer_size();
    let mut config = range.with_sample_rate(cpal::SampleRate(48000)).config();
    if let cpal::SupportedBufferSize::Range { min, max } = buffer {
        config.buffer_size = cpal::BufferSize::Fixed(256u32.clamp(min, max));
    }
    let started = Instant::now();
    let observed = Arc::new(Mutex::new(Vec::<f64>::new()));
    let captured = observed.clone();
    let mut last = 0.0;
    let record = device.build_input_stream(
        &config,
        move |data: &[f32], _| {
            let peak = data.iter().fold(0f32, |p, v| p.max(v.abs()));
            let now = started.elapsed().as_secs_f64() * 1000.;
            if peak > 0.06 {
                if now - last > 500. {
                    captured.lock().unwrap().push(now);
                }
                last = now;
            }
        },
        |e| eprintln!("Capture error: {e}"),
        None,
    )?;
    record.play()?;
    let socket = TcpStream::connect((host.as_str(), 54346))?;
    socket.set_nodelay(true)?;
    socket.set_read_timeout(Some(Duration::from_secs(5)))?;
    let mut connection: Box<dyn Connection> = {
        let dir = directories::ProjectDirs::from("", "AirRemote", "Companion")
            .context("No config directory")?;
        let identity: serde_json::Value =
            serde_json::from_slice(&std::fs::read(dir.config_dir().join("identity.json"))?)?;
        let cert: Vec<u8> = serde_json::from_value(identity["cert"].clone())?;
        let mut roots = rustls::RootCertStore::empty();
        roots.add(rustls::pki_types::CertificateDer::from(cert))?;
        let tls = rustls::ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth();
        let client = rustls::ClientConnection::new(Arc::new(tls), "airremote.local".try_into()?)?;
        let mut stream = rustls::StreamOwned::new(client, socket);
        stream.write_all(
            format!("{{\"token\":{},\"kind\":\"audio\"}}\n", identity["token"]).as_bytes(),
        )?;
        stream.flush()?;
        let mut response = [0; 3];
        stream.read_exact(&mut response)?;
        if &response != b"OK\n" {
            bail!("Authentication failed");
        }
        Box::new(stream)
    };
    connection.write_all(b"AirRemoteA1")?;
    connection.flush()?;
    let mut hello = [0; 11];
    connection.read_exact(&mut hello)?;
    if &hello != b"AirRemoteA2" {
        bail!("Audio handshake failed");
    }
    let began = Instant::now();
    let mut sent = Vec::new();
    for frame in 0..1200u32 {
        let target = Duration::from_millis(frame as u64 * 10);
        while began.elapsed() < target {
            let remaining = target.saturating_sub(began.elapsed());
            if remaining > Duration::from_millis(2) {
                thread::sleep(Duration::from_millis(1));
            } else {
                std::hint::spin_loop();
            }
        }
        let signal = frame >= 100 && (frame - 100) % 200 < 10;
        let mut packet = vec![10, 128, 15];
        for i in 0..480 {
            let value = if signal {
                0.3 * (std::f32::consts::TAU * 1000. * i as f32 / 48000.).sin()
            } else {
                0.
            };
            packet.extend(value.to_le_bytes());
        }
        packet.extend([16, 128, 247, 2, 24, 1, 32, 4]);
        if frame >= 100 && (frame - 100) % 200 == 0 {
            sent.push(started.elapsed().as_secs_f64() * 1000.);
        }
        connection.write_all(&(packet.len() as u32).to_be_bytes())?;
        connection.write_all(&packet)?;
        connection.flush()?;
    }
    thread::sleep(Duration::from_millis(500));
    println!(
        "{}",
        serde_json::json!({"receiver":"AirRemote","sent_ms":sent,"observed_ms":*observed.lock().unwrap()})
    );
    Ok(())
}
