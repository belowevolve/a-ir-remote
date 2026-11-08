//! Normal keyboard input. Field contents and accessibility events never gate typing.
use crate::text_input::{byte_at_utf16, Plan, Shadow};
use std::{
    mem::size_of,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
};
use unicode_segmentation::UnicodeSegmentation;
use windows_sys::Win32::UI::{
    Input::KeyboardAndMouse::{
        GetAsyncKeyState, MapVirtualKeyW, SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT,
        KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP, KEYEVENTF_UNICODE, MAPVK_VK_TO_VSC, VK_BACK,
        VK_CONTROL, VK_LEFT, VK_LWIN, VK_MENU, VK_RETURN, VK_RIGHT, VK_RWIN, VK_SHIFT,
    },
    WindowsAndMessaging::GetForegroundWindow,
};

pub struct Session {
    cancelled: Arc<AtomicBool>,
    initial: Shadow,
}
impl Session {
    pub fn begin(cancelled: Arc<AtomicBool>) -> Result<Self, &'static str> {
        // Best effort, once. Unsupported apps still receive ordinary keyboard events.
        let initial = crate::initial_text::read().unwrap_or_default();
        Ok(Self { cancelled, initial })
    }
    pub fn initial(&self) -> Result<Shadow, &'static str> {
        Ok(self.initial.clone())
    }
    pub fn after_key(&self, before: &Shadow) -> Option<Shadow> {
        for delay in [40, 60, 100] {
            std::thread::sleep(std::time::Duration::from_millis(delay));
            if let Some(snapshot) = crate::initial_text::read() {
                if snapshot != *before || delay == 100 {
                    return Some(snapshot);
                }
            }
        }
        None
    }
    pub fn cancelled(&self) -> bool {
        self.cancelled.load(Ordering::SeqCst)
    }
    pub fn apply(&mut self, previous: &Shadow, plan: &Plan) -> Result<(), &'static str> {
        if self.cancelled() {
            return Err("session_replaced");
        }
        unsafe {
            if GetForegroundWindow().is_null() {
                return Err("input_unavailable");
            }
            if [VK_SHIFT, VK_CONTROL, VK_MENU, VK_LWIN, VK_RWIN]
                .iter()
                .any(|key| GetAsyncKeyState(*key as i32) < 0)
            {
                return Err("input_busy");
            }
        }
        let mut inputs = Vec::new();
        let key = |inputs: &mut Vec<INPUT>, code: u16| {
            inputs.push(input(code, 0, 0));
            inputs.push(input(code, 0, KEYEVENTF_KEYUP));
        };
        if let Some((name, count)) = &plan.key {
            if name == "clear" {
                inputs.push(input(VK_CONTROL, 0, 0));
                key(&mut inputs, 0x41);
                inputs.push(input(VK_CONTROL, 0, KEYEVENTF_KEYUP));
                key(&mut inputs, VK_BACK);
            } else {
                for _ in 0..*count {
                    key(
                        &mut inputs,
                        if name == "enter" { VK_RETURN } else { VK_BACK },
                    );
                }
            }
        } else {
            let mut caret = previous.start;
            if previous.start != previous.end {
                key(&mut inputs, VK_LEFT); // Collapse the previous selection to its left edge.
            }
            if let Some(replacement) = &plan.replacement {
                move_to(&mut inputs, &previous.text, caret, replacement.start)?;
                select_to(
                    &mut inputs,
                    &previous.text,
                    replacement.start,
                    replacement.end,
                )?;
                if replacement.text.is_empty() {
                    key(&mut inputs, VK_BACK);
                } else {
                    for unit in replacement.text.encode_utf16() {
                        if unit == 10 {
                            key(&mut inputs, VK_RETURN);
                            continue;
                        }
                        inputs.push(input(0, unit, KEYEVENTF_UNICODE));
                        inputs.push(input(0, unit, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
                    }
                }
                caret = replacement.start + replacement.text.encode_utf16().count() as u32;
            }
            move_to(&mut inputs, &plan.next.text, caret, plan.next.start)?;
            select_to(&mut inputs, &plan.next.text, plan.next.start, plan.next.end)?;
        }
        if inputs.is_empty() {
            return Ok(());
        }
        if inputs.len() > 200_000 {
            return Err("unsupported_edit");
        }
        if self.cancelled() {
            return Err("session_replaced");
        }
        // One batch preserves ordering, including selection, replacement and key releases.
        let sent = unsafe {
            SendInput(
                inputs.len() as u32,
                inputs.as_ptr(),
                size_of::<INPUT>() as i32,
            )
        };
        if sent != inputs.len() as u32 {
            let releases: Vec<_> = inputs
                .iter()
                .filter_map(|event| unsafe {
                    let k = event.Anonymous.ki;
                    (k.dwFlags & KEYEVENTF_KEYUP == 0)
                        .then(|| input(k.wVk, k.wScan, k.dwFlags | KEYEVENTF_KEYUP))
                })
                .collect();
            unsafe {
                SendInput(
                    releases.len() as u32,
                    releases.as_ptr(),
                    size_of::<INPUT>() as i32,
                );
            }
            return Err("input_failed");
        }
        Ok(())
    }
}
fn input(key: u16, scan: u16, flags: u32) -> INPUT {
    let scan = if key != 0 {
        unsafe { MapVirtualKeyW(key as u32, MAPVK_VK_TO_VSC) as u16 }
    } else {
        scan
    };
    let flags = flags
        | if key == VK_LEFT || key == VK_RIGHT {
            KEYEVENTF_EXTENDEDKEY
        } else {
            0
        };
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: key,
                wScan: scan,
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}
fn steps(text: &str, from: u32, to: u32) -> Result<usize, &'static str> {
    let start = byte_at_utf16(text, from.min(to)).ok_or("invalid_selection")?;
    let end = byte_at_utf16(text, from.max(to)).ok_or("invalid_selection")?;
    Ok(text[start..end].graphemes(true).count())
}
fn move_to(inputs: &mut Vec<INPUT>, text: &str, from: u32, to: u32) -> Result<(), &'static str> {
    let key = if from > to { VK_LEFT } else { VK_RIGHT };
    for _ in 0..steps(text, from, to)? {
        inputs.push(input(key, 0, 0));
        inputs.push(input(key, 0, KEYEVENTF_KEYUP));
    }
    Ok(())
}
fn select_to(inputs: &mut Vec<INPUT>, text: &str, from: u32, to: u32) -> Result<(), &'static str> {
    if from != to {
        inputs.push(input(VK_SHIFT, 0, 0));
        move_to(inputs, text, from, to)?;
        inputs.push(input(VK_SHIFT, 0, KEYEVENTF_KEYUP));
    }
    Ok(())
}
