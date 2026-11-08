use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader, Read, Write};

pub const MAX_TEXT_BYTES: usize = 64 * 1024;
// JSON can represent each text byte as a six-byte escape (for example, \u0001).
const MAX_REQUEST_BYTES: usize = MAX_TEXT_BYTES * 6 + 32;

pub fn capabilities() -> &'static [&'static str] {
    if cfg!(windows) {
        &["clipboard_text_v1"]
    } else {
        &[]
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    text: String,
}

#[derive(Debug, Serialize, PartialEq)]
pub struct Outcome {
    clipboard_copied: bool,
    paste_sent: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<&'static str>,
}

impl Outcome {
    fn failed(error: &'static str) -> Self {
        Self {
            clipboard_copied: false,
            paste_sent: false,
            error: Some(error),
        }
    }

    #[cfg(windows)]
    fn copied(error: Option<&'static str>) -> Self {
        Self {
            clipboard_copied: true,
            paste_sent: error.is_none(),
            error,
        }
    }
}

fn read_request(reader: &mut impl Read) -> Result<Request, &'static str> {
    let mut bytes = Vec::new();
    BufReader::new(reader)
        .take((MAX_REQUEST_BYTES + 1) as u64)
        .read_until(b'\n', &mut bytes)
        .map_err(|_| "invalid_request")?;
    if bytes.last() != Some(&b'\n') || bytes.len() > MAX_REQUEST_BYTES {
        return Err("invalid_request");
    }
    let request: Request = serde_json::from_slice(&bytes).map_err(|_| "invalid_request")?;
    if request.text.is_empty() || request.text.len() > MAX_TEXT_BYTES || request.text.contains('\0')
    {
        return Err("invalid_text");
    }
    Ok(request)
}

pub fn receive(stream: &mut (impl Read + Write)) -> std::io::Result<()> {
    // Neither parser errors nor responses include clipboard contents.
    let outcome = match read_request(stream) {
        Ok(request) => copy_and_paste(&request.text),
        Err(error) => Outcome::failed(error),
    };
    serde_json::to_writer(&mut *stream, &outcome)?;
    stream.write_all(b"\n")?;
    stream.flush()
}

#[cfg(not(windows))]
fn copy_and_paste(_: &str) -> Outcome {
    Outcome::failed("unsupported")
}

#[cfg(any(windows, test))]
fn windows_text(text: &str) -> Vec<u16> {
    let mut result = Vec::new();
    for unit in text.encode_utf16() {
        // CF_UNICODETEXT uses CRLF. Preserve existing CRLF rather than doubling CR.
        if unit == 10 && result.last() != Some(&13) {
            result.push(13);
        }
        result.push(unit);
    }
    result.push(0);
    result
}

#[cfg(windows)]
fn copy_and_paste(text: &str) -> Outcome {
    use std::{mem::size_of, ptr::null_mut, sync::Mutex};
    use windows_sys::Win32::{
        Foundation::{GlobalFree, HWND},
        System::{
            DataExchange::{CloseClipboard, EmptyClipboard, OpenClipboard, SetClipboardData},
            Memory::{GlobalAlloc, GlobalLock, GlobalUnlock, GMEM_MOVEABLE},
        },
        UI::{
            Input::KeyboardAndMouse::{
                GetAsyncKeyState, SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT,
                KEYEVENTF_KEYUP, VK_CONTROL, VK_LWIN, VK_MENU, VK_RWIN, VK_SHIFT, VK_V,
            },
            WindowsAndMessaging::{
                CreateWindowExW, DestroyWindow, GetForegroundWindow, HWND_MESSAGE,
            },
        },
    };
    // Prevent concurrent requests from replacing one another's clipboard before Ctrl+V.
    static TRANSFER: Mutex<()> = Mutex::new(());
    let Ok(_transfer) = TRANSFER.try_lock() else {
        return Outcome::failed("busy");
    };
    struct Owner(HWND);
    impl Drop for Owner {
        fn drop(&mut self) {
            unsafe { DestroyWindow(self.0) };
        }
    }
    struct OpenClipboardGuard;
    impl Drop for OpenClipboardGuard {
        fn drop(&mut self) {
            unsafe { CloseClipboard() };
        }
    }
    fn key(code: u16, up: bool) -> INPUT {
        INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: code,
                    wScan: 0,
                    dwFlags: if up { KEYEVENTF_KEYUP } else { 0 },
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        }
    }
    unsafe {
        let foreground = GetForegroundWindow();
        // A real, non-activating owner is required: OpenClipboard(NULL) followed by
        // EmptyClipboard makes SetClipboardData fail. STATIC is a system window class.
        let owner = Owner(CreateWindowExW(
            0,
            windows_sys::core::w!("STATIC"),
            windows_sys::core::w!("Air Remote Clipboard"),
            0,
            0,
            0,
            0,
            0,
            HWND_MESSAGE,
            null_mut(),
            null_mut(),
            null_mut(),
        ));
        if owner.0.is_null() {
            return Outcome::failed("clipboard_failed");
        }
        // Allocate before emptying the user's existing clipboard.
        let wide = windows_text(text);
        let memory = GlobalAlloc(GMEM_MOVEABLE, wide.len() * size_of::<u16>());
        if memory.is_null() {
            return Outcome::failed("clipboard_failed");
        }
        let data = GlobalLock(memory);
        if data.is_null() {
            GlobalFree(memory);
            return Outcome::failed("clipboard_failed");
        }
        std::ptr::copy_nonoverlapping(wide.as_ptr(), data.cast::<u16>(), wide.len());
        GlobalUnlock(memory);
        if OpenClipboard(owner.0) == 0 {
            GlobalFree(memory);
            return Outcome::failed("clipboard_busy");
        }
        let opened = OpenClipboardGuard;
        const CF_UNICODETEXT: u32 = 13;
        if EmptyClipboard() == 0 || SetClipboardData(CF_UNICODETEXT, memory).is_null() {
            GlobalFree(memory);
            return Outcome::failed("clipboard_failed");
        }
        // Windows owns memory after SetClipboardData succeeds. Close before injecting
        // input so the receiving application can read the text immediately.
        drop(opened);
        if foreground.is_null() || GetForegroundWindow() != foreground {
            return Outcome::copied(Some("focus_changed"));
        }
        if [VK_CONTROL, VK_SHIFT, VK_MENU, VK_LWIN, VK_RWIN, VK_V]
            .iter()
            .any(|&code| GetAsyncKeyState(i32::from(code)) < 0)
        {
            return Outcome::copied(Some("input_busy"));
        }
        let inputs = [
            key(VK_CONTROL, false),
            key(VK_V, false),
            key(VK_V, true),
            key(VK_CONTROL, true),
        ];
        let sent = SendInput(
            inputs.len() as u32,
            inputs.as_ptr(),
            size_of::<INPUT>() as i32,
        );
        if sent != inputs.len() as u32 {
            if sent > 0 {
                // A partial insertion must not leave our keys down. Never retry paste.
                let releases = [key(VK_V, true), key(VK_CONTROL, true)];
                SendInput(
                    releases.len() as u32,
                    releases.as_ptr(),
                    size_of::<INPUT>() as i32,
                );
            }
            return Outcome::copied(Some("paste_blocked"));
        }
        // This confirms input injection, not that a particular application accepted paste.
        Outcome::copied(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(text: &str) -> Vec<u8> {
        let mut bytes = serde_json::to_vec(&serde_json::json!({"text": text})).unwrap();
        bytes.push(b'\n');
        bytes
    }

    #[test]
    fn preserves_unicode_quotes_and_multiple_lines() {
        let text = "Привет 👋\n\"one\"\t二\r\n";
        assert_eq!(
            read_request(&mut request(text).as_slice()).unwrap().text,
            text
        );
    }

    #[test]
    fn windows_text_uses_crlf_and_preserves_surrogate_pairs() {
        assert_eq!(
            windows_text("a\n👋\r\nb"),
            "a\r\n👋\r\nb\0".encode_utf16().collect::<Vec<_>>()
        );
    }

    #[test]
    fn limit_is_utf8_bytes_not_characters_or_json_escapes() {
        for text in [
            "x".repeat(MAX_TEXT_BYTES),
            "я".repeat(MAX_TEXT_BYTES / 2),
            "\u{1}".repeat(MAX_TEXT_BYTES),
        ] {
            assert!(read_request(&mut request(&text).as_slice()).is_ok());
            assert!(read_request(&mut request(&(text + "x")).as_slice()).is_err());
        }
    }

    #[test]
    fn rejects_empty_nul_malformed_unicode_and_non_text_requests() {
        for bytes in [
            request(""),
            request("before\0after"),
            b"{\"text\":\"\\ud800\"}\n".to_vec(),
            b"{\"text\":123}\n".to_vec(),
            b"{\"text\":\"ok\",\"paste\":true}\n".to_vec(),
            b"{\"text\":\"ok\"}".to_vec(),
            b"{\"text\":\"\xff\"}\n".to_vec(),
        ] {
            assert!(read_request(&mut bytes.as_slice()).is_err());
        }
    }

    #[test]
    fn bounds_the_wire_message_before_parsing() {
        let bytes = vec![b' '; MAX_REQUEST_BYTES + 100];
        assert!(read_request(&mut bytes.as_slice()).is_err());
    }
}
