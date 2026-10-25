//! Shared Android mark rendered for Windows shortcuts, taskbar and tray.
use anyhow::Result;
use windows::{
    core::{Interface, PCWSTR},
    Win32::{
        System::Com::{
            CoCreateInstance, CoInitializeEx, CoUninitialize, IPersistFile, CLSCTX_INPROC_SERVER,
            COINIT_APARTMENTTHREADED,
        },
        UI::Shell::{IShellLinkW, SHChangeNotify, ShellLink, SHCNE_UPDATEITEM, SHCNF_PATHW},
    },
};
fn rounded(x: f64, y: f64, bounds: [f64; 4], radius: f64) -> bool {
    let [l, t, r, b] = bounds;
    let dx = (l + radius - x).max(0.).max(x - (r - radius));
    let dy = (t + radius - y).max(0.).max(y - (b - radius));
    x >= l && x <= r && y >= t && y <= b && dx * dx + dy * dy <= radius * radius
}
pub fn pixels(size: usize, foreground: u32, detail: u32, background: u32) -> Vec<u8> {
    let mut out = Vec::with_capacity(size * size * 4);
    for y in 0..size {
        for x in 0..size {
            let mut sum = [0u32; 4];
            for sy in 0..4 {
                for sx in 0..4 {
                    let px = (x as f64 + (sx as f64 + 0.5) / 4.) * 108. / size as f64;
                    let py = (y as f64 + (sy as f64 + 0.5) / 4.) * 108. / size as f64;
                    if !rounded(px, py, [2., 2., 106., 106.], 24.) {
                        continue;
                    }
                    let mut c = background;
                    if rounded(px, py, [27., 16.5, 81., 91.5], 13.5) {
                        c = foreground;
                    }
                    if rounded(px, py, [51., 27., 57., 54.], 3.)
                        || rounded(px, py, [40.5, 37.5, 67.5, 43.5], 3.)
                        || rounded(px, py, [39., 64.5, 48., 73.5], 3.)
                        || rounded(px, py, [60., 64.5, 69., 73.5], 3.)
                    {
                        c = detail;
                    }
                    sum[0] += (c >> 16) & 255;
                    sum[1] += (c >> 8) & 255;
                    sum[2] += c & 255;
                    sum[3] += 1;
                }
            }
            for c in &sum[..3] {
                out.push(c.checked_div(sum[3]).unwrap_or(0) as u8);
            }
            out.push((sum[3] * 255 / 16) as u8);
        }
    }
    out
}
fn ico(colors: (u32, u32, u32)) -> Vec<u8> {
    let sizes = [16usize, 32, 48, 64, 128, 256];
    let mut images = Vec::new();
    for size in sizes {
        let data = pixels(size, colors.0, colors.1, colors.2);
        let mut image = Vec::new();
        for n in [
            40u32,
            size as u32,
            (size * 2) as u32,
            0x00200001,
            0,
            (size * size * 4) as u32,
            0,
            0,
            0,
            0,
        ] {
            image.extend(n.to_le_bytes());
        }
        for row in data.chunks_exact(size * 4).rev() {
            image.extend(row);
        }
        image.resize(image.len() + size.div_ceil(32) * 4 * size, 0);
        images.push(image);
    }
    let mut out = vec![0, 0, 1, 0, sizes.len() as u8, 0];
    let mut offset = 6 + 16 * sizes.len();
    for (size, image) in sizes.into_iter().zip(&images) {
        out.extend([size as u8, size as u8, 0, 0, 1, 0, 32, 0]);
        out.extend((image.len() as u32).to_le_bytes());
        out.extend((offset as u32).to_le_bytes());
        offset += image.len();
    }
    for image in images {
        out.extend(image);
    }
    out
}
fn wide(path: &std::path::Path) -> Vec<u16> {
    path.as_os_str()
        .to_string_lossy()
        .encode_utf16()
        .chain(Some(0))
        .collect()
}
pub fn update_shortcut(colors: (u32, u32, u32)) -> Result<()> {
    let dir = crate::identity::directory()?;
    let icon = dir.join(format!("icon-{:06x}-{:06x}.ico", colors.0, colors.2));
    if !icon.exists() {
        std::fs::write(&icon, ico(colors))?;
    }
    let link = std::path::PathBuf::from(std::env::var("APPDATA")?)
        .join("Microsoft/Windows/Start Menu/Programs/Air Remote/Air Remote.lnk");
    // Portable runs must not create Start menu entries.
    if !link.exists() {
        return Ok(());
    }
    unsafe {
        let initialized = CoInitializeEx(None, COINIT_APARTMENTTHREADED).is_ok();
        let result = (|| -> Result<()> {
            let shell: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)?;
            let persist: IPersistFile = shell.cast()?;
            persist.Load(
                PCWSTR(wide(&link).as_ptr()),
                windows::Win32::System::Com::STGM_READWRITE,
            )?;
            shell.SetIconLocation(PCWSTR(wide(&icon).as_ptr()), 0)?;
            persist.Save(PCWSTR(wide(&link).as_ptr()), true)?;
            SHChangeNotify(
                SHCNE_UPDATEITEM,
                SHCNF_PATHW,
                Some(wide(&link).as_ptr() as _),
                None,
            );
            Ok(())
        })();
        if initialized {
            CoUninitialize();
        }
        result
    }
}
