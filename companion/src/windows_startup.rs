//! Per-user startup; no administrator privileges or scheduled tasks required.
use anyhow::{bail, Result};
use std::ptr::{null, null_mut};
use windows_sys::Win32::System::Registry::*;
fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}
const RUN: &str = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
const NAME: &str = "AirRemote";
pub fn enabled() -> bool {
    unsafe {
        let mut size = 0;
        RegGetValueW(
            HKEY_CURRENT_USER,
            wide(RUN).as_ptr(),
            wide(NAME).as_ptr(),
            RRF_RT_REG_SZ,
            null_mut(),
            null_mut(),
            &mut size,
        ) == 0
            && size > 2
    }
}
pub fn set_enabled(enabled: bool) -> Result<()> {
    unsafe {
        let mut key = null_mut();
        let result = RegCreateKeyExW(
            HKEY_CURRENT_USER,
            wide(RUN).as_ptr(),
            0,
            null(),
            0,
            KEY_SET_VALUE,
            null(),
            &mut key,
            null_mut(),
        );
        if result != 0 {
            bail!("Не удалось изменить автозапуск: {result}");
        }
        let result = if enabled {
            let exe = std::env::current_exe();
            match exe {
                Ok(exe) => {
                    let value = wide(&format!("\"{}\" --background", exe.display()));
                    RegSetValueExW(
                        key,
                        wide(NAME).as_ptr(),
                        0,
                        REG_SZ,
                        value.as_ptr() as _,
                        (value.len() * 2) as u32,
                    )
                }
                Err(error) => {
                    RegCloseKey(key);
                    return Err(error.into());
                }
            }
        } else {
            RegDeleteValueW(key, wide(NAME).as_ptr())
        };
        RegCloseKey(key);
        if result != 0 && !(result == 2 && !enabled) {
            bail!("Не удалось изменить автозапуск: {result}");
        }
        Ok(())
    }
}
