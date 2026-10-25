#![cfg_attr(windows, windows_subsystem = "windows")]
mod audio;
mod identity;
mod platform;
mod server;
#[cfg(windows)]
mod windows_audio;
#[cfg(windows)]
mod windows_icon;
#[cfg(windows)]
mod windows_startup;
#[cfg(windows)]
mod windows_ui;
fn run() -> anyhow::Result<()> {
    if std::env::args().any(|a| a == "--check-cable") {
        let found = audio::outputs()?
            .iter()
            .any(|n| n.to_lowercase().contains("cable input"));
        std::process::exit(if found { 0 } else { 2 });
    }
    #[cfg(windows)]
    if std::env::args().any(|a| a == "--quit") {
        windows_ui::exit_existing();
        return Ok(());
    }
    #[cfg(windows)]
    if windows_ui::activate_existing() {
        return Ok(());
    }
    let app = server::App::new(identity::Identity::load()?)?;
    server::start(app.clone())?;
    #[cfg(windows)]
    windows_ui::run(app)?;
    #[cfg(not(windows))]
    {
        eprintln!("Air Remote: audio receiver on {}:54346. Keyboard sync and pairing UI currently require Windows.",app.host);
        loop {
            std::thread::park();
        }
    }
    Ok(())
}
fn main() {
    if let Err(error) = run() {
        #[cfg(windows)]
        windows_ui::error(&format!("{error:#}"));
        #[cfg(not(windows))]
        eprintln!("{error:#}");
    }
}
