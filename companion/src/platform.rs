use serde::Serialize;
#[derive(Clone, PartialEq, Serialize)]
pub struct KeyboardState {
    pub language: String,
    pub layout_id: String,
}
#[cfg(windows)]
pub fn keyboard_state() -> Option<KeyboardState> {
    use windows_sys::Win32::UI::{
        Input::KeyboardAndMouse::GetKeyboardLayout,
        WindowsAndMessaging::{GetForegroundWindow, GetWindowThreadProcessId},
    };
    unsafe {
        let window = GetForegroundWindow();
        if window.is_null() {
            return None;
        }
        let thread = GetWindowThreadProcessId(window, std::ptr::null_mut());
        let layout = GetKeyboardLayout(thread) as usize;
        let language = match layout & 0x3ff {
            0x19 => "ru",
            0x09 => "en",
            _ => "other",
        };
        Some(KeyboardState {
            language: language.into(),
            layout_id: format!("{layout:016x}"),
        })
    }
}
#[cfg(not(windows))]
pub fn keyboard_state() -> Option<KeyboardState> {
    None
}
