//! Optional read-only context on opening and explicit keys. No polling or field mutation.
use crate::text_input::{normalized_context, Shadow, MAX_TEXT_BYTES};
use std::ptr::null_mut;
use windows::{
    core::Interface,
    Win32::{
        System::Com::{
            CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER,
            COINIT_MULTITHREADED,
        },
        UI::Accessibility::{
            CUIAutomation8, IUIAutomation, IUIAutomation2, IUIAutomationTextPattern,
            IUIAutomationTextRange, IUIAutomationValuePattern, TextPatternRangeEndpoint,
            TextPatternRangeEndpoint_End as END, TextPatternRangeEndpoint_Start as START,
            UIA_TextPatternId, UIA_ValuePatternId,
        },
    },
};
use windows_sys::Win32::{
    Foundation::HWND,
    UI::{Controls::EM_GETSEL, WindowsAndMessaging::*},
};
struct Apartment;
impl Drop for Apartment {
    fn drop(&mut self) {
        unsafe { CoUninitialize() }
    }
}

pub fn read() -> Option<Shadow> {
    unsafe {
        let window = GetForegroundWindow();
        let thread = GetWindowThreadProcessId(window, null_mut());
        let mut info: GUITHREADINFO = std::mem::zeroed();
        info.cbSize = std::mem::size_of::<GUITHREADINFO>() as u32;
        if window.is_null() || GetGUIThreadInfo(thread, &mut info) == 0 {
            return None;
        }
        if let Some(snapshot) = read_window(info.hwndFocus) {
            return (GetForegroundWindow() == window).then_some(snapshot);
        }
        CoInitializeEx(None, COINIT_MULTITHREADED).ok().ok()?;
        let _apartment = Apartment;
        let automation: IUIAutomation =
            CoCreateInstance(&CUIAutomation8, None, CLSCTX_INPROC_SERVER).ok()?;
        let timed: IUIAutomation2 = automation.cast().ok()?;
        timed.SetConnectionTimeout(300).ok()?;
        timed.SetTransactionTimeout(200).ok()?;
        let field = automation.GetFocusedElement().ok()?;
        if field.CurrentIsPassword().ok()?.as_bool() {
            return None;
        }
        if !field.CurrentHasKeyboardFocus().ok()?.as_bool() {
            return None;
        }
        // WinUI Notepad exposes a native RichEdit handle although its GUI thread's
        // focused HWND is a hosting bridge. Its UIA DocumentRange can lag Value.
        if let Ok(native) = field.CurrentNativeWindowHandle() {
            if GetAncestor(native.0, GA_ROOT) == window {
                if let Some(snapshot) = read_window(native.0) {
                    return (GetForegroundWindow() == window).then_some(snapshot);
                }
            }
        }
        let pattern: IUIAutomationTextPattern =
            field.GetCurrentPatternAs(UIA_TextPatternId).ok()?;
        let document = pattern.DocumentRange().ok()?;
        if !automation
            .CompareElements(&document.GetEnclosingElement().ok()?, &field)
            .ok()?
            .as_bool()
        {
            return None;
        }
        let text = document
            .GetText(MAX_TEXT_BYTES as i32 + 1)
            .ok()?
            .to_string();
        if text.encode_utf16().count() > MAX_TEXT_BYTES {
            return None;
        }
        // Chromium may expose a stale accessibility document. Never prefill that
        // when its editable value disagrees (including a phantom single letter).
        if let Ok(value) =
            field.GetCurrentPatternAs::<IUIAutomationValuePattern>(UIA_ValuePatternId)
        {
            let actual = value.CurrentValue().ok()?.to_string();
            if text != actual && !(text == "\u{fffc}" && actual.is_empty()) {
                return None;
            }
        }
        if !field.CurrentHasKeyboardFocus().ok()?.as_bool() {
            return None;
        }
        let ranges = pattern.GetSelection().ok()?;
        if ranges.Length().ok()? != 1 {
            return None;
        }
        let selection = ranges.GetElement(0).ok()?;
        let offset = |endpoint: TextPatternRangeEndpoint| -> Option<u32> {
            if selection
                .CompareEndpoints(endpoint, &document, START)
                .ok()?
                < 0
                || selection.CompareEndpoints(endpoint, &document, END).ok()? > 0
            {
                return None;
            }
            let prefix: IUIAutomationTextRange = document.Clone().ok()?;
            prefix.MoveEndpointByRange(END, &selection, endpoint).ok()?;
            let prefix = prefix.GetText(MAX_TEXT_BYTES as i32 + 1).ok()?.to_string();
            text.starts_with(&prefix)
                .then(|| prefix.encode_utf16().count() as u32)
        };
        let (start, end) = (offset(START)?, offset(END)?);
        let mut text = text;
        if text == "\u{fffc}" && start == 0 && end == 0 {
            let value: IUIAutomationValuePattern =
                field.GetCurrentPatternAs(UIA_ValuePatternId).ok()?;
            if value.CurrentValue().ok()?.is_empty() {
                text.clear();
            }
        }
        if GetForegroundWindow() != window
            || !automation
                .CompareElements(&automation.GetFocusedElement().ok()?, &field)
                .ok()?
                .as_bool()
        {
            return None;
        }
        normalized_context(text, start, end, true)
    }
}
unsafe fn read_window(window: HWND) -> Option<Shadow> {
    let mut name = [0u16; 64];
    let len = GetClassNameW(window, name.as_mut_ptr(), name.len() as i32);
    let name = String::from_utf16_lossy(&name[..len.max(0) as usize]).to_ascii_lowercase();
    if !(name == "edit" || name.starts_with("richedit"))
        || GetWindowLongPtrW(window, GWL_STYLE) & ES_PASSWORD as isize != 0
    {
        return None;
    }
    let length = message(window, WM_GETTEXTLENGTH, 0, 0)?;
    if length > MAX_TEXT_BYTES {
        return None;
    }
    let mut text = vec![0u16; length + 1];
    if message(window, WM_GETTEXT, text.len(), text.as_mut_ptr() as isize)? != length {
        return None;
    }
    let (mut start, mut end) = (0u32, 0u32);
    message(
        window,
        EM_GETSEL,
        &mut start as *mut u32 as usize,
        &mut end as *mut u32 as isize,
    )?;
    normalized_context(
        String::from_utf16(&text[..length]).ok()?,
        start,
        end,
        name == "edit",
    )
}

fn message(window: HWND, kind: u32, w: usize, l: isize) -> Option<usize> {
    let mut result = 0;
    (unsafe {
        SendMessageTimeoutW(
            window,
            kind,
            w,
            l,
            SMTO_ABORTIFHUNG | SMTO_BLOCK,
            100,
            &mut result,
        )
    } != 0)
        .then_some(result)
}
