use crate::{audio, identity::Identity, platform};
use anyhow::{bail, Context, Result};
use serde::Deserialize;
use std::{
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    sync::{
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};

pub struct App {
    pub identity: Identity,
    pub host: String,
    pub outputs: Vec<String>,
    pub output: Mutex<String>,
    pub status: Mutex<String>,
    pub audio_active: AtomicBool,
    pub dropped: Arc<AtomicU64>,
    connections: AtomicUsize,
    controls: AtomicUsize,
}
impl App {
    pub fn new(identity: Identity) -> Result<Arc<Self>> {
        let outputs = audio::outputs()?;
        let saved = std::fs::read(crate::identity::directory()?.join("output.json"))
            .ok()
            .and_then(|data| serde_json::from_slice::<String>(&data).ok())
            .filter(|name| outputs.contains(name));
        let output = saved
            .or_else(|| {
                outputs
                    .iter()
                    .find(|n| n.to_lowercase().contains("cable input"))
                    .cloned()
            })
            .unwrap_or_default();
        let addresses = local_ip_address::list_afinet_netifas()?;
        let host = addresses
            .iter()
            .filter_map(|(_, a)| match a {
                std::net::IpAddr::V4(v) if v.is_private() && !v.is_loopback() => Some(*v),
                _ => None,
            })
            .min_by_key(|a| if a.octets()[0] == 192 { 0 } else { 1 })
            .context("Connect to a local network")?
            .to_string();
        Ok(Arc::new(Self {
            identity,
            host,
            outputs,
            output: Mutex::new(output),
            status: Mutex::new("Ожидание телефона".into()),
            audio_active: AtomicBool::new(false),
            dropped: Arc::new(AtomicU64::new(0)),
            connections: AtomicUsize::new(0),
            controls: AtomicUsize::new(0),
        }))
    }
    pub fn phone_connections(&self) -> usize {
        self.controls.load(Ordering::SeqCst) + usize::from(self.audio_active.load(Ordering::SeqCst))
    }
    pub fn select_output(&self, name: String) -> Result<()> {
        std::fs::write(
            crate::identity::directory()?.join("output.json"),
            serde_json::to_vec(&name)?,
        )?;
        *self.output.lock().unwrap() = name;
        self.status("Выход сохранён. Применится при следующем подключении");
        Ok(())
    }
    pub fn status(&self, text: impl Into<String>) {
        *self.status.lock().unwrap() = text.into();
    }
}
#[derive(Deserialize)]
struct Hello {
    token: String,
    kind: String,
}
pub fn read_line(reader: &mut impl Read) -> Result<Vec<u8>> {
    let mut data = Vec::new();
    let mut b = [0];
    loop {
        reader.read_exact(&mut b)?;
        if b[0] == b'\n' {
            return Ok(data);
        }
        if data.len() >= 1024 {
            bail!("Request too large")
        }
        data.push(b[0]);
    }
}
pub fn start(app: Arc<App>) -> Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", 54346)).context("Port 54346 is already in use")?;
    thread::spawn(move || {
        for socket in listener.incoming() {
            let Ok(socket) = socket else { continue };
            if app.connections.fetch_add(1, Ordering::SeqCst) >= 8 {
                app.connections.fetch_sub(1, Ordering::SeqCst);
                continue;
            }
            let app = app.clone();
            thread::spawn(move || {
                let _ = session(socket, &app);
                app.connections.fetch_sub(1, Ordering::SeqCst);
            });
        }
    });
    Ok(())
}
fn session(socket: TcpStream, app: &App) -> Result<()> {
    socket.set_nodelay(true)?;
    socket.set_read_timeout(Some(Duration::from_secs(5)))?;
    socket.set_write_timeout(Some(Duration::from_secs(3)))?;
    let connection = rustls::ServerConnection::new(app.identity.tls.clone())?;
    let mut stream = rustls::StreamOwned::new(connection, socket);
    let hello: Hello = serde_json::from_slice(&read_line(&mut stream)?)?;
    if !app.identity.accepts(&hello.token) {
        bail!("Unauthenticated client");
    }
    match hello.kind.as_str() {
        "control" => {
            stream.write_all(b"OK\n")?;
            stream.flush()?;
            let mut previous = None;
            let mut heartbeat = Instant::now();
            app.controls.fetch_add(1, Ordering::SeqCst);
            if !app.audio_active.load(Ordering::SeqCst) {
                app.status("Телефон подключён");
            }
            struct Control<'a>(&'a App);
            impl Drop for Control<'_> {
                fn drop(&mut self) {
                    if self.0.controls.fetch_sub(1, Ordering::SeqCst) == 1
                        && !self.0.audio_active.load(Ordering::SeqCst)
                    {
                        self.0.status("Ожидание телефона");
                    }
                }
            }
            let _control = Control(app);
            loop {
                let state = platform::keyboard_state();
                if state != previous {
                    if let Some(ref state) = state {
                        let mut data = serde_json::to_vec(state)?;
                        data.push(b'\n');
                        stream.write_all(&data)?;
                        stream.flush()?;
                    }
                    previous = state;
                }
                if heartbeat.elapsed() >= Duration::from_secs(3) {
                    stream.write_all(b"{\"ping\":true}\n")?;
                    stream.flush()?;
                    heartbeat = Instant::now();
                }
                thread::sleep(Duration::from_millis(150));
            }
        }
        "audio" => {
            if app.audio_active.swap(true, Ordering::SeqCst) {
                bail!("Microphone already connected");
            }
            struct Active<'a>(&'a AtomicBool);
            impl Drop for Active<'_> {
                fn drop(&mut self) {
                    self.0.store(false, Ordering::SeqCst);
                }
            }
            let _active = Active(&app.audio_active);
            let output = app.output.lock().unwrap().clone();
            if output.is_empty() {
                stream.write_all(b"ERROR Select CABLE Input on PC\n")?;
                stream.flush()?;
                bail!("No audio output selected");
            }
            stream.write_all(b"OK\n")?;
            stream.flush()?;
            app.status("Микрофон подключён");
            let result = audio::receive(&mut stream, &output, app.dropped.clone());
            if let Err(error) = &result {
                if error.downcast_ref::<std::io::Error>().is_none() {
                    app.status(format!("Ошибка микрофона: {error}"));
                    return result;
                }
            }
            app.status(if app.controls.load(Ordering::SeqCst) > 0 {
                "Телефон подключён"
            } else {
                "Ожидание телефона"
            });
            result
        }
        _ => bail!("Unknown connection kind"),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_unbounded_requests() {
        assert!(read_line(&mut &vec![b'x'; 1026][..]).is_err());
    }
    #[test]
    fn preserves_binary_payload() {
        let mut r = &b"hello\n\x00\x01"[..];
        assert_eq!(read_line(&mut r).unwrap(), b"hello");
        assert_eq!(r, b"\x00\x01");
    }
}
