use anyhow::{Context, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rand::RngCore;
use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{fs, path::PathBuf, sync::Arc};

#[derive(Serialize, Deserialize)]
struct Stored {
    cert: Vec<u8>,
    key: Vec<u8>,
    token: String,
}
pub struct Identity {
    pub tls: Arc<rustls::ServerConfig>,
    pub fingerprint: String,
    pub token: String,
}
pub fn directory() -> Result<PathBuf> {
    Ok(directories::ProjectDirs::from("", "AirRemote", "Companion")
        .context("Cannot locate application data")?
        .config_dir()
        .to_owned())
}
impl Identity {
    pub fn load() -> Result<Self> {
        let directory = directory()?;
        fs::create_dir_all(&directory)?;
        let path: PathBuf = directory.join("identity.json");
        let stored: Stored = if path.exists() {
            serde_json::from_slice(&fs::read(&path)?)?
        } else {
            let generated = rcgen::generate_simple_self_signed(vec!["airremote.local".into()])?;
            let mut secret = [0u8; 32];
            rand::thread_rng().fill_bytes(&mut secret);
            let stored = Stored {
                cert: generated.cert.der().to_vec(),
                key: generated.key_pair.serialize_der(),
                token: URL_SAFE_NO_PAD.encode(secret),
            };
            let mut options = fs::OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            use std::io::Write;
            options
                .open(path)?
                .write_all(&serde_json::to_vec(&stored)?)?;
            stored
        };
        let fingerprint = Sha256::digest(&stored.cert)
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        let tls = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(
                vec![CertificateDer::from(stored.cert)],
                PrivatePkcs8KeyDer::from(stored.key).into(),
            )?;
        Ok(Self {
            tls: Arc::new(tls),
            fingerprint,
            token: stored.token,
        })
    }
    pub fn pairing_uri(&self, host: &str) -> String {
        format!(
            "airremote://{host}:54346/{}/{}",
            self.fingerprint, self.token
        )
    }
    pub fn accepts(&self, token: &str) -> bool {
        // Fixed-size digest comparison avoids token-dependent early returns.
        let a = Sha256::digest(token.as_bytes());
        let b = Sha256::digest(self.token.as_bytes());
        a.iter().zip(b.iter()).fold(0u8, |d, (x, y)| d | (x ^ y)) == 0
    }
}
