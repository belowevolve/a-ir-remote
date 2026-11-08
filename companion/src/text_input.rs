//! Explicit paired-phone keyboard input with optional bounded, non-password field context.
use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader, Read, Write};
use unicode_segmentation::UnicodeSegmentation;

pub const MAX_TEXT_BYTES: usize = 16 * 1024;
const MAX_REQUEST_BYTES: usize = MAX_TEXT_BYTES * 6 + 256;

#[cfg(windows)]
pub use crate::windows_text_input::Session;
#[cfg(not(windows))]
pub struct Session;
#[cfg(not(windows))]
impl Session {
    pub fn begin(_: std::sync::Arc<std::sync::atomic::AtomicBool>) -> Result<Self, &'static str> {
        Err("unsupported")
    }
    pub fn initial(&self) -> Result<Shadow, &'static str> {
        Err("unsupported")
    }
    fn apply(&mut self, _: &Shadow, _: &Plan) -> Result<(), &'static str> {
        Err("unsupported")
    }
    fn cancelled(&self) -> bool {
        false
    }
    fn after_key(&self, _: &Shadow) -> Option<Shadow> {
        None
    }
}

/// Optional initial field context and edits made during this explicit phone session.
#[derive(Clone, Default, PartialEq)]
pub struct Shadow {
    pub text: String,
    pub start: u32,
    pub end: u32,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    revision: u64,
    text: Option<String>,
    start: Option<u32>,
    end: Option<u32>,
    key: Option<String>,
    #[serde(default = "one")]
    count: u32,
}
fn one() -> u32 {
    1
}
pub struct Replacement {
    pub start: u32,
    pub end: u32,
    pub text: String,
}
pub struct Plan {
    pub next: Shadow,
    pub replacement: Option<Replacement>,
    pub key: Option<(String, u32)>,
}

pub fn supported_text(text: &str) -> bool {
    text.len() <= MAX_TEXT_BYTES && !text.chars().any(|ch| ch.is_control() && ch != '\n')
}

/// Standard Edit selections count CRLF as two units; RichEdit counts one paragraph break.
#[cfg(any(windows, test))]
pub fn normalized_context(
    text: String,
    start: u32,
    end: u32,
    crlf_selection: bool,
) -> Option<Shadow> {
    let units: Vec<_> = text.encode_utf16().collect();
    let offset = |at: u32| -> Option<u32> {
        let at = at as usize;
        if !crlf_selection {
            return Some(at as u32);
        }
        if at > units.len()
            || (at > 0 && at < units.len() && units[at - 1] == 13 && units[at] == 10)
        {
            return None;
        }
        Some(
            at as u32
                - units[..at]
                    .windows(2)
                    .filter(|pair| pair == &[13, 10])
                    .count() as u32,
        )
    };
    let (start, end) = (offset(start)?, offset(end)?);
    let text = text.replace("\r\n", "\n").replace('\r', "\n");
    (supported_text(&text) && boundary(&text, start) && boundary(&text, end)).then_some(Shadow {
        text,
        start: start.min(end),
        end: start.max(end),
    })
}

pub fn byte_at_utf16(text: &str, offset: u32) -> Option<usize> {
    let mut units = 0;
    for (byte, ch) in text.char_indices() {
        if units == offset {
            return Some(byte);
        }
        units += ch.len_utf16() as u32;
    }
    (units == offset).then_some(text.len())
}
fn boundary(text: &str, offset: u32) -> bool {
    let Some(byte) = byte_at_utf16(text, offset) else {
        return false;
    };
    byte == text.len() || text.grapheme_indices(true).any(|(at, _)| at == byte)
}
fn plan(old: &Shadow, request: &Request) -> Result<Plan, &'static str> {
    if let Some(key) = &request.key {
        if request.text.is_some()
            || request.start.is_some()
            || request.end.is_some()
            || !(1..=64).contains(&request.count)
            || !matches!(key.as_str(), "enter" | "backspace" | "clear")
        {
            return Err("invalid_request");
        }
        return Ok(Plan {
            next: Shadow::default(),
            replacement: None,
            key: Some((key.clone(), request.count)),
        });
    }
    let text = request.text.as_ref().ok_or("invalid_request")?;
    let start = request.start.ok_or("invalid_request")?;
    let end = request.end.ok_or("invalid_request")?;
    if text.len() > MAX_TEXT_BYTES {
        return Err("invalid_text");
    }
    // Newlines are text, never an Enter/submit action. Other controls remain unsupported.
    if !supported_text(text) {
        return Err("unsupported_edit");
    }
    if !boundary(text, start) || !boundary(text, end) {
        return Err("invalid_selection");
    }
    let replacement = if old.text == *text {
        None
    } else {
        let a: Vec<&str> = old.text.graphemes(true).collect();
        let b: Vec<&str> = text.graphemes(true).collect();
        let prefix = a.iter().zip(&b).take_while(|(a, b)| a == b).count();
        let suffix = a[prefix..]
            .iter()
            .rev()
            .zip(b[prefix..].iter().rev())
            .take_while(|(a, b)| a == b)
            .count();
        let before = a[..prefix]
            .iter()
            .map(|s| s.encode_utf16().count() as u32)
            .sum();
        let after = a[..a.len() - suffix]
            .iter()
            .map(|s| s.encode_utf16().count() as u32)
            .sum();
        Some(Replacement {
            start: before,
            end: after,
            text: b[prefix..b.len() - suffix].concat(),
        })
    };
    Ok(Plan {
        replacement,
        key: None,
        next: Shadow {
            text: text.clone(),
            start: start.min(end),
            end: start.max(end),
        },
    })
}
#[derive(Serialize)]
struct Reply {
    revision: u64,
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<&'static str>,
    #[serde(flatten, skip_serializing_if = "Option::is_none")]
    context: Option<Context>,
}
#[derive(Serialize)]
struct Context {
    text: String,
    start: u32,
    end: u32,
}
fn read_request(
    reader: &mut impl BufRead,
    pending: &mut Vec<u8>,
) -> Result<Option<Request>, &'static str> {
    let data = reader.fill_buf().map_err(|error| match error.kind() {
        std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut => "read_idle",
        _ => "connection_lost",
    })?;
    if data.is_empty() {
        return if pending.is_empty() {
            Ok(None)
        } else {
            Err("invalid_request")
        };
    }
    let length = data
        .iter()
        .position(|&b| b == b'\n')
        .map_or(data.len(), |n| n + 1);
    if pending.len() + length > MAX_REQUEST_BYTES {
        return Err("invalid_request");
    }
    let complete = data[length - 1] == b'\n';
    pending.extend_from_slice(&data[..length]);
    reader.consume(length);
    if !complete {
        return Err("read_more");
    }
    let result = serde_json::from_slice(pending)
        .map(Some)
        .map_err(|_| "invalid_request");
    pending.clear();
    result
}
pub fn receive(stream: &mut (impl Read + Write), mut session: Session) -> std::io::Result<()> {
    let mut reader = BufReader::new(stream);
    let mut revision = 0u64;
    let mut shadow = session.initial().map_err(std::io::Error::other)?;
    #[derive(Serialize)]
    struct Initial<'a> {
        revision: u64,
        text: &'a str,
        start: u32,
        end: u32,
    }
    serde_json::to_writer(
        reader.get_mut(),
        &Initial {
            revision: 0,
            text: &shadow.text,
            start: shadow.start,
            end: shadow.end,
        },
    )?;
    reader.get_mut().write_all(b"\n")?;
    reader.get_mut().flush()?;
    let mut pending = Vec::new();
    loop {
        if session.cancelled() {
            return Ok(());
        }
        let request = match read_request(&mut reader, &mut pending) {
            Ok(None) => return Ok(()),
            Ok(Some(request)) => request,
            Err("read_more") => {
                continue;
            }
            Err("read_idle") => continue,
            Err(error) => {
                if session.cancelled() {
                    return Ok(());
                }
                serde_json::to_writer(
                    reader.get_mut(),
                    &Reply {
                        revision: 0,
                        ok: false,
                        error: Some(error),
                        context: None,
                    },
                )?;
                reader.get_mut().write_all(b"\n")?;
                return reader.get_mut().flush();
            }
        };
        let mut context = None;
        let result = if revision.checked_add(1) != Some(request.revision) {
            Err("invalid_revision")
        } else {
            plan(&shadow, &request).and_then(|plan| {
                session.apply(&shadow, &plan)?;
                let observed = if plan.key.is_some() {
                    session.after_key(&shadow)
                } else {
                    None
                };
                shadow = observed.unwrap_or(plan.next);
                if plan.key.is_some() {
                    context = Some(Context {
                        text: shadow.text.clone(),
                        start: shadow.start,
                        end: shadow.end,
                    });
                }
                Ok(())
            })
        };
        if session.cancelled() {
            return Ok(());
        }
        serde_json::to_writer(
            reader.get_mut(),
            &Reply {
                revision: request.revision,
                ok: result.is_ok(),
                error: result.err(),
                context,
            },
        )?;
        reader.get_mut().write_all(b"\n")?;
        reader.get_mut().flush()?;
        if result.is_err() {
            return Ok(());
        }
        revision = request.revision;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn snapshot(text: &str, start: u32, end: u32) -> Request {
        Request {
            revision: 1,
            text: Some(text.into()),
            start: Some(start),
            end: Some(end),
            key: None,
            count: 1,
        }
    }
    #[test]
    fn native_edit_and_richedit_paragraph_offsets_normalize_once() {
        let standard = normalized_context("first\r\nsecond\r\nthird".into(), 20, 20, true).unwrap();
        let rich = normalized_context("first\r\nsecond\r\nthird".into(), 18, 18, false).unwrap();
        assert_eq!(standard.text, "first\nsecond\nthird");
        assert_eq!((standard.start, standard.end), (18, 18));
        assert_eq!((rich.start, rich.end), (18, 18));
        assert!(normalized_context("x\r\ny".into(), 2, 2, true).is_none());
        assert!(normalized_context("x".into(), 2, 2, false).is_none());
    }

    #[test]
    fn autocorrection_keeps_unchanged_prefix_and_suffix() {
        let old = Shadow {
            text: "hello teh world".into(),
            start: 9,
            end: 9,
        };
        let plan = plan(&old, &snapshot("hello the world", 9, 9)).unwrap();
        let r = plan.replacement.unwrap();
        assert_eq!((r.start, r.end, r.text.as_str()), (7, 9, "he"));
    }
    #[test]
    fn complex_emoji_replacement_uses_whole_cluster_and_utf16_offsets() {
        let old = Shadow {
            text: "A👨‍👩‍👧‍👦B".into(),
            start: 12,
            end: 12,
        };
        let r = plan(&old, &snapshot("A😀B", 3, 3))
            .unwrap()
            .replacement
            .unwrap();
        assert_eq!((r.start, r.end, r.text.as_str()), (1, 12, "😀"));
        assert!(plan(&old, &snapshot("A😀B", 2, 2)).is_err());
        assert!(plan(&old, &snapshot("e\u{301}", 1, 1)).is_err());
    }
    #[test]
    fn controls_and_invalid_selection_fail_before_execution() {
        for text in ["a\r\nb", "a\tb", "a\0b"] {
            assert!(plan(&Shadow::default(), &snapshot(text, 0, 0)).is_err());
        }
        let plan = plan(&Shadow::default(), &snapshot("你好", 2, 0)).unwrap();
        let s = plan.next;
        assert_eq!((s.start, s.end), (0, 2));
    }
    #[test]
    fn native_enter_resets_only_the_phone_draft_and_boundary_delete_is_explicit() {
        let old = Shadow {
            text: "hello".into(),
            start: 5,
            end: 5,
        };
        let mut request = Request {
            revision: 2,
            text: None,
            start: None,
            end: None,
            key: Some("enter".into()),
            count: 1,
        };
        let planned = plan(&old, &request).unwrap();
        assert!(planned.next.text.is_empty());
        assert_eq!(planned.key, Some(("enter".into(), 1)));
        request.key = Some("backspace".into());
        assert!(plan(&old, &request).is_ok());
        assert!(plan(&Shadow::default(), &request).is_ok());
        request.count = 65;
        assert!(plan(&Shadow::default(), &request).is_err());
        request.count = 1;
        request.text = Some("do not mix".into());
        assert!(plan(&Shadow::default(), &request).is_err());
    }
    #[test]
    fn request_reader_preserves_following_frame_and_bounds_wire() {
        let mut reader = BufReader::new(
            &b"{\"revision\":1,\"key\":\"enter\"}\n{\"revision\":2,\"key\":\"enter\"}\n"[..],
        );
        let mut pending = Vec::new();
        assert_eq!(
            read_request(&mut reader, &mut pending)
                .unwrap()
                .unwrap()
                .revision,
            1
        );
        assert_eq!(
            read_request(&mut reader, &mut pending)
                .unwrap()
                .unwrap()
                .revision,
            2
        );
        let bytes = vec![b'x'; MAX_REQUEST_BYTES + 2];
        let mut reader = BufReader::new(bytes.as_slice());
        loop {
            match read_request(&mut reader, &mut pending) {
                Err("read_more") => continue,
                Err("invalid_request") => break,
                _ => panic!("unbounded frame accepted"),
            }
        }
    }
    #[test]
    fn idle_poll_keeps_partial_json_without_replay() {
        struct Pieces(std::collections::VecDeque<std::io::Result<Vec<u8>>>);
        impl Read for Pieces {
            fn read(&mut self, output: &mut [u8]) -> std::io::Result<usize> {
                match self.0.pop_front() {
                    Some(Ok(bytes)) => {
                        output[..bytes.len()].copy_from_slice(&bytes);
                        Ok(bytes.len())
                    }
                    Some(Err(error)) => Err(error),
                    None => Ok(0),
                }
            }
        }
        let source = Pieces(std::collections::VecDeque::from([
            Ok(b"{\"revision\":".to_vec()),
            Err(std::io::Error::from(std::io::ErrorKind::TimedOut)),
            Ok(b"1,\"key\":\"enter\"}\n".to_vec()),
        ]));
        let mut reader = BufReader::new(source);
        let mut pending = Vec::new();
        assert!(matches!(
            read_request(&mut reader, &mut pending),
            Err("read_more")
        ));
        let bytes = pending.clone();
        assert!(matches!(
            read_request(&mut reader, &mut pending),
            Err("read_idle")
        ));
        assert_eq!(pending, bytes);
        assert_eq!(
            read_request(&mut reader, &mut pending)
                .unwrap()
                .unwrap()
                .revision,
            1
        );
        assert!(pending.is_empty());
        assert!(read_request(&mut reader, &mut pending).unwrap().is_none());
    }
}
