use crate::server::App;
use anyhow::{bail, Result};
use qrcode::{Color, QrCode};
use std::{
    mem::size_of,
    ptr::{null, null_mut},
    sync::{Arc, OnceLock},
};
use windows_sys::Win32::{
    Foundation::*,
    Graphics::{Dwm::*, Gdi::*},
    System::{DataExchange::*, LibraryLoader::GetModuleHandleW, Memory::*, Registry::*},
    UI::{Controls::*, HiDpi::*, Shell::*, WindowsAndMessaging::*},
};

static APP: OnceLock<Arc<App>> = OnceLock::new();
const COPY: i32 = 101;
const OUTPUT: i32 = 102;
const EXIT: i32 = 104;
const BLUETOOTH: i32 = 105;
const SOUND: i32 = 106;
const CABLE: i32 = 107;
const PAIR: i32 = 108;
const STARTUP: i32 = 109;
const SHOW: i32 = 110;
const TRAY: u32 = WM_APP + 1;
unsafe fn fit_dpi(dpi: u32, window: HWND) -> u32 {
    let mut info: MONITORINFO = std::mem::zeroed();
    info.cbSize = size_of::<MONITORINFO>() as u32;
    let monitor = MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST);
    if GetMonitorInfoW(monitor, &mut info) == 0 {
        return dpi;
    }
    let height = info.rcWork.bottom - info.rcWork.top;
    let width = info.rcWork.right - info.rcWork.left;
    dpi.min((height * 96 / 680) as u32)
        .min((width * 96 / 600) as u32)
        .max(48)
}
fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(Some(0)).collect()
}
fn rgb(r: u32, g: u32, b: u32) -> u32 {
    r | g << 8 | b << 16
}
fn blend(a: u32, b: u32, amount: u32) -> u32 {
    let channel = |shift: u32| {
        (((a >> shift) & 255u32) * (100 - amount) + ((b >> shift) & 255u32) * amount) / 100
    };
    channel(0) | channel(8) << 8 | channel(16) << 16
}
struct Visual {
    dpi: i32,
    dark: bool,
    card: u32,
    text: u32,
    muted: u32,
    primary: u32,
    on_primary: u32,
    tinted: u32,
    background_brush: HBRUSH,
    card_brush: HBRUSH,
    normal: HFONT,
    heading: HFONT,
    theme_icon: HICON,
    show_pairing: bool,
    connections: usize,
    small: HFONT,
    qr: QrCode,
    status: String,
}
impl Visual {
    unsafe fn new(dpi: u32) -> Self {
        let mut light: u32 = 0;
        let mut len = 4;
        RegGetValueW(
            HKEY_CURRENT_USER,
            wide("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize").as_ptr(),
            wide("AppsUseLightTheme").as_ptr(),
            RRF_RT_REG_DWORD,
            null_mut(),
            &mut light as *mut _ as _,
            &mut len,
        );
        let dark = light == 0;
        let mut accent = 0xff91edbc;
        let mut opaque = 0;
        DwmGetColorizationColor(&mut accent, &mut opaque);
        let accent = rgb(
            (accent >> 16) & 255u32,
            (accent >> 8) & 255u32,
            accent & 255u32,
        );
        let background = blend(
            if dark {
                rgb(16, 20, 17)
            } else {
                rgb(248, 250, 247)
            },
            accent,
            4,
        );
        let card = blend(
            if dark {
                rgb(28, 33, 29)
            } else {
                rgb(238, 242, 237)
            },
            accent,
            5,
        );
        let primary = blend(
            accent,
            if dark { 0xffffff } else { 0 },
            if dark { 56 } else { 35 },
        );
        let font = |height: i32, weight: i32| {
            CreateFontW(
                -(height * dpi as i32 / 96),
                0,
                0,
                0,
                weight,
                0,
                0,
                0,
                DEFAULT_CHARSET as _,
                0,
                0,
                CLEARTYPE_QUALITY as _,
                0,
                wide("Segoe UI").as_ptr(),
            )
        };
        // Match Android's light accent mark, dark details and muted accent background.
        let icon_colors = (
            blend(accent, 0xffffff, 72),
            blend(accent, 0, 65),
            blend(accent, rgb(35, 35, 35), 55),
        );
        let _ = crate::windows_icon::update_shortcut(icon_colors);
        Self {
            dpi: dpi as i32,
            dark,
            card,
            text: if dark {
                rgb(233, 238, 232)
            } else {
                rgb(25, 31, 27)
            },
            muted: if dark {
                rgb(184, 196, 185)
            } else {
                rgb(83, 96, 86)
            },
            primary,
            on_primary: if dark { rgb(14, 31, 25) } else { 0xffffff },
            tinted: blend(card, accent, if dark { 28 } else { 18 }),
            background_brush: CreateSolidBrush(background),
            card_brush: CreateSolidBrush(card),
            normal: font(16, 400),
            heading: font(20, 600),
            theme_icon: themed_icon(icon_colors.0, icon_colors.1, icon_colors.2),
            show_pairing: APP.get().unwrap().phone_connections() == 0,
            connections: APP.get().unwrap().phone_connections(),
            small: font(13, 400),
            qr: QrCode::new(
                APP.get()
                    .unwrap()
                    .identity
                    .pairing_uri(&APP.get().unwrap().host),
            )
            .unwrap(),
            status: String::new(),
        }
    }
    fn px(&self, v: i32) -> i32 {
        v * self.dpi / 96
    }
    fn rect(&self, x: i32, y: i32, w: i32, h: i32) -> RECT {
        RECT {
            left: self.px(x),
            top: self.px(y),
            right: self.px(x + w),
            bottom: self.px(y + h),
        }
    }
    unsafe fn text(&self, dc: HDC, text: &str, area: RECT, font: HFONT, color: u32, flags: u32) {
        let old = SelectObject(dc, font as _);
        SetTextColor(dc, color);
        SetBkMode(dc, TRANSPARENT as _);
        let mut area = area;
        let text = wide(text);
        DrawTextW(dc, text.as_ptr(), (text.len() - 1) as _, &mut area, flags);
        SelectObject(dc, old);
    }
    unsafe fn round(&self, dc: HDC, rect: RECT, color: u32, radius: i32) {
        let brush = CreateSolidBrush(color);
        let old = SelectObject(dc, brush as _);
        let pen = SelectObject(dc, GetStockObject(NULL_PEN));
        RoundRect(
            dc,
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            self.px(radius * 2),
            self.px(radius * 2),
        );
        SelectObject(dc, pen);
        SelectObject(dc, old);
        DeleteObject(brush as _);
    }
}
impl Drop for Visual {
    fn drop(&mut self) {
        unsafe {
            DestroyIcon(self.theme_icon);
            for object in [
                self.background_brush as HGDIOBJ,
                self.card_brush as _,
                self.normal as _,
                self.heading as _,
                self.small as _,
            ] {
                DeleteObject(object);
            }
        }
    }
}
unsafe fn visual<'a>(window: HWND) -> &'a Visual {
    &*(GetWindowLongPtrW(window, GWLP_USERDATA) as *const Visual)
}
unsafe fn icon() -> HICON {
    LoadImageW(
        GetModuleHandleW(null()),
        1usize as _,
        IMAGE_ICON,
        256,
        256,
        LR_SHARED,
    ) as _
}
unsafe fn themed_icon(primary: u32, detail: u32, background: u32) -> HICON {
    let mut info: BITMAPINFO = std::mem::zeroed();
    info.bmiHeader.biSize = size_of::<BITMAPINFOHEADER>() as u32;
    info.bmiHeader.biWidth = 64;
    info.bmiHeader.biHeight = -64;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    let mut bits = null_mut();
    let bitmap = CreateDIBSection(null_mut(), &info, DIB_RGB_COLORS, &mut bits, null_mut(), 0);
    if bitmap.is_null() {
        return CopyIcon(icon());
    }
    let pixels = std::slice::from_raw_parts_mut(bits as *mut u32, 64 * 64);
    for (pixel, source) in pixels.iter_mut().zip(
        crate::windows_icon::pixels(64, primary, detail, background)
            .as_chunks::<4>()
            .0,
    ) {
        let a = source[3] as u32;
        *pixel = (source[0] as u32 * a / 255)
            | (source[1] as u32 * a / 255) << 8
            | (source[2] as u32 * a / 255) << 16
            | a << 24;
    }
    let mask_bits = [0u8; 512];
    let mask = CreateBitmap(64, 64, 1, 1, mask_bits.as_ptr() as _);
    let icon_info = ICONINFO {
        fIcon: 1,
        xHotspot: 0,
        yHotspot: 0,
        hbmMask: mask,
        hbmColor: bitmap,
    };
    let result = CreateIconIndirect(&icon_info);
    DeleteObject(bitmap as _);
    DeleteObject(mask as _);
    if result.is_null() {
        CopyIcon(icon())
    } else {
        result
    }
}
pub fn exit_existing() {
    unsafe {
        let window = FindWindowW(wide("AirRemoteCompanion").as_ptr(), null());
        if !window.is_null() {
            SendMessageW(window, WM_COMMAND, EXIT as usize, 0);
        }
    }
}
pub fn activate_existing() -> bool {
    unsafe {
        let window = FindWindowW(wide("AirRemoteCompanion").as_ptr(), null());
        if window.is_null() {
            return false;
        }
        ShowWindow(window, SW_RESTORE);
        SetForegroundWindow(window);
        true
    }
}
pub fn error(message: &str) {
    unsafe {
        MessageBoxW(
            null_mut(),
            wide(message).as_ptr(),
            wide("Air Remote").as_ptr(),
            MB_OK | MB_ICONERROR,
        );
    }
}
unsafe fn open(window: HWND, target: &str) {
    ShellExecuteW(
        window,
        wide("open").as_ptr(),
        wide(target).as_ptr(),
        null(),
        null(),
        SW_SHOWNORMAL,
    );
}
unsafe fn controls(window: HWND) {
    let v = visual(window);
    for (id, text) in [
        (COPY, "Скопировать код"),
        (PAIR, "Добавить телефон"),
        (BLUETOOTH, "Настройки Bluetooth"),
        (SOUND, "Настройки звука"),
        (CABLE, "Панель кабеля"),
        (EXIT, "Завершить работу"),
        (STARTUP, "Запускать с Windows"),
        (OUTPUT, "Выбрать выход микрофона"),
    ] {
        let child = CreateWindowExW(
            0,
            wide("BUTTON").as_ptr(),
            wide(text).as_ptr(),
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_OWNERDRAW as u32,
            0,
            0,
            0,
            0,
            window,
            id as _,
            GetModuleHandleW(null()),
            null(),
        );
        SendMessageW(child, WM_SETFONT, v.normal as _, 0);
    }
    let selected = APP.get().unwrap().output.lock().unwrap().clone();
    if !selected.is_empty() {
        SetWindowTextW(GetDlgItem(window, OUTPUT), wide(&selected).as_ptr());
    }
    layout(window);
}
unsafe fn layout(window: HWND) {
    let v = visual(window);
    for id in [OUTPUT, SOUND, CABLE, BLUETOOTH, EXIT, STARTUP] {
        ShowWindow(
            GetDlgItem(window, id),
            if v.show_pairing { SW_HIDE } else { SW_SHOW },
        );
    }
    ShowWindow(
        GetDlgItem(window, COPY),
        if v.show_pairing { SW_SHOW } else { SW_HIDE },
    );
    SetWindowTextW(
        GetDlgItem(window, PAIR),
        wide(if v.show_pairing {
            "Готово"
        } else {
            "Добавить телефон"
        })
        .as_ptr(),
    );
    SendMessageW(window, WM_SETICON, ICON_BIG as _, v.theme_icon as _);
    SendMessageW(window, WM_SETICON, ICON_SMALL as _, v.theme_icon as _);
    for (id, x, y, w, h) in [
        (
            PAIR,
            if v.show_pairing { 288 } else { 32 },
            if v.show_pairing { 544 } else { 96 },
            if v.show_pairing { 240 } else { 496 },
            40,
        ),
        (COPY, 32, 544, 240, 40),
        (OUTPUT, 32, 236, 496, 48),
        (SOUND, 32, 296, 242, 44),
        (CABLE, 286, 296, 242, 44),
        (BLUETOOTH, 32, 440, 496, 44),
        (EXIT, 324, 512, 220, 40),
        (STARTUP, 16, 512, 296, 40),
    ] {
        let child = GetDlgItem(window, id);
        SetWindowPos(
            child,
            null_mut(),
            v.px(x),
            v.px(y),
            v.px(w),
            v.px(h),
            SWP_NOZORDER,
        );
        SendMessageW(child, WM_SETFONT, v.normal as _, 1);
    }
    let dark = v.dark as i32;
    DwmSetWindowAttribute(window, 20, &dark as *const _ as _, 4);
}
unsafe fn copy_code(window: HWND) {
    let app = APP.get().unwrap();
    let text = wide(&app.identity.pairing_uri(&app.host));
    if OpenClipboard(window) == 0 {
        return;
    }
    let memory = GlobalAlloc(GMEM_MOVEABLE, text.len() * 2);
    if !memory.is_null() {
        let data = GlobalLock(memory);
        if !data.is_null() {
            std::ptr::copy_nonoverlapping(text.as_ptr(), data as *mut u16, text.len());
            GlobalUnlock(memory);
            EmptyClipboard();
            if SetClipboardData(13, memory).is_null() {
                GlobalFree(memory);
            }
        } else {
            GlobalFree(memory);
        }
    }
    CloseClipboard();
    SetWindowTextW(GetDlgItem(window, COPY), wide("Код скопирован").as_ptr());
    SetTimer(window, 2, 2000, None);
}
unsafe fn resize(window: HWND) {
    let v = visual(window);
    let mut area = v.rect(0, 0, 560, if v.show_pairing { 600 } else { 568 });
    AdjustWindowRectExForDpi(
        &mut area,
        GetWindowLongPtrW(window, GWL_STYLE) as u32,
        0,
        WS_EX_CONTROLPARENT,
        GetDpiForWindow(window),
    );
    let mut old: RECT = std::mem::zeroed();
    GetWindowRect(window, &mut old);
    let mut info: MONITORINFO = std::mem::zeroed();
    info.cbSize = size_of::<MONITORINFO>() as u32;
    GetMonitorInfoW(
        MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST),
        &mut info,
    );
    let width = area.right - area.left;
    let height = area.bottom - area.top;
    SetWindowPos(
        window,
        null_mut(),
        old.left
            .max(info.rcWork.left)
            .min((info.rcWork.right - width).max(info.rcWork.left)),
        old.top
            .max(info.rcWork.top)
            .min((info.rcWork.bottom - height).max(info.rcWork.top)),
        width,
        height,
        SWP_NOZORDER | SWP_NOACTIVATE,
    );
    layout(window);
    InvalidateRect(window, null(), 0);
}
unsafe fn paint(window: HWND) {
    let mut paint: PAINTSTRUCT = std::mem::zeroed();
    let dc = BeginPaint(window, &mut paint);
    let v = visual(window);
    let mut area: RECT = std::mem::zeroed();
    GetClientRect(window, &mut area);
    let mem = CreateCompatibleDC(dc);
    let bitmap = CreateCompatibleBitmap(dc, area.right, area.bottom);
    let old = SelectObject(mem, bitmap as _);
    FillRect(mem, &area, v.background_brush);
    let app = APP.get().unwrap();
    if v.show_pairing {
        v.round(mem, v.rect(16, 16, 528, 516), v.card, 20);
        v.text(
            mem,
            "Подключить телефон",
            v.rect(32, 28, 496, 28),
            v.heading,
            v.text,
            DT_SINGLELINE,
        );
        let count = v.qr.width() as i32;
        let scale = (v.px(432) / (count + 8)).max(1);
        let side = (count + 8) * scale;
        let left = (v.px(560) - side) / 2;
        let top = v.px(76) + (v.px(432) - side) / 2;
        FillRect(
            mem,
            &RECT {
                left,
                top,
                right: left + side,
                bottom: top + side,
            },
            GetStockObject(WHITE_BRUSH) as _,
        );
        for y in 0..count {
            for x in 0..count {
                if v.qr[(x as usize, y as usize)] == Color::Dark {
                    FillRect(
                        mem,
                        &RECT {
                            left: left + (x + 4) * scale,
                            top: top + (y + 4) * scale,
                            right: left + (x + 5) * scale,
                            bottom: top + (y + 5) * scale,
                        },
                        GetStockObject(BLACK_BRUSH) as _,
                    );
                }
            }
        }
    } else {
        for (title, y, height) in [
            ("Телефон", 16, 136),
            ("Микрофон", 164, 192),
            ("Клавиатура и мышь", 368, 132),
        ] {
            v.round(mem, v.rect(16, y, 528, height), v.card, 20);
            v.text(
                mem,
                title,
                v.rect(32, y + 12, 496, 28),
                v.heading,
                v.text,
                DT_SINGLELINE,
            );
        }
        let connected = app.phone_connections() > 0;
        v.text(
            mem,
            if connected {
                "Wi-Fi · подключён"
            } else {
                "Wi-Fi · нет подключения"
            },
            v.rect(32, 56, 496, 24),
            v.normal,
            v.muted,
            DT_SINGLELINE,
        );
        v.text(
            mem,
            if app.outputs.is_empty() {
                "Компонент микрофона не установлен"
            } else if v.status.starts_with("Ошибка") || v.status.starts_with("Выход сохранён")
            {
                &v.status
            } else if app.audio_active.load(std::sync::atomic::Ordering::SeqCst) {
                "Передача звука · CABLE Output"
            } else {
                "В приложениях: CABLE Output"
            },
            v.rect(32, 204, 496, 24),
            v.small,
            v.muted,
            DT_SINGLELINE | DT_END_ELLIPSIS,
        );
        v.text(
            mem,
            "Bluetooth · работает независимо от компаньона",
            v.rect(32, 408, 496, 24),
            v.small,
            v.muted,
            DT_SINGLELINE,
        );
    }
    BitBlt(dc, 0, 0, area.right, area.bottom, mem, 0, 0, SRCCOPY);
    SelectObject(mem, old);
    DeleteObject(bitmap as _);
    DeleteDC(mem);
    EndPaint(window, &paint);
}
unsafe fn draw_button(window: HWND, item: &DRAWITEMSTRUCT) {
    let v = visual(window);
    FillRect(
        item.hDC,
        &item.rcItem,
        if matches!(item.CtlID as i32, EXIT | STARTUP) || v.show_pairing {
            v.background_brush
        } else {
            v.card_brush
        },
    );
    let primary = item.CtlID == COPY as u32;
    let mut color = if primary { v.primary } else { v.tinted };
    if item.itemState & ODS_SELECTED != 0
        || (item.itemState & ODS_FOCUS != 0 && item.itemState & ODS_NOFOCUSRECT == 0)
    {
        color = blend(color, v.text, 12);
    }
    v.round(item.hDC, item.rcItem, color, 18);
    let mut label = [0u16; 128];
    let len = GetWindowTextW(item.hwndItem, label.as_mut_ptr(), 128);
    let text = String::from_utf16_lossy(&label[..len as usize]);
    let mut text_rect = item.rcItem;
    let startup = item.CtlID == STARTUP as u32;
    if startup {
        let enabled = crate::windows_startup::enabled();
        let x = item.rcItem.right - v.px(60);
        let y = (item.rcItem.top + item.rcItem.bottom) / 2 - v.px(12);
        let track = RECT {
            left: x,
            top: y,
            right: x + v.px(44),
            bottom: y + v.px(24),
        };
        v.round(
            item.hDC,
            track,
            if enabled { v.primary } else { v.muted },
            12,
        );
        let left = x + v.px(if enabled { 23 } else { 3 });
        v.round(
            item.hDC,
            RECT {
                left,
                top: y + v.px(3),
                right: left + v.px(18),
                bottom: y + v.px(21),
            },
            if enabled { v.on_primary } else { v.card },
            9,
        );
        text_rect.left += v.px(16);
        text_rect.right = x - v.px(8);
    }
    let output = item.CtlID == OUTPUT as u32;
    if output {
        text_rect.left += v.px(16);
        text_rect.right -= v.px(36);
    }
    v.text(
        item.hDC,
        &text,
        text_rect,
        v.normal,
        if primary { v.on_primary } else { v.text },
        (if output || startup {
            DT_LEFT | DT_END_ELLIPSIS
        } else {
            DT_CENTER
        }) | DT_SINGLELINE
            | DT_VCENTER,
    );
    if output {
        let x = item.rcItem.right - v.px(20);
        let y = (item.rcItem.top + item.rcItem.bottom) / 2;
        let pen = CreatePen(PS_SOLID, v.px(2).max(1), v.text);
        let old = SelectObject(item.hDC, pen as _);
        let points = [
            POINT {
                x: x - v.px(4),
                y: y - v.px(2),
            },
            POINT { x, y: y + v.px(2) },
            POINT {
                x: x + v.px(4),
                y: y - v.px(2),
            },
        ];
        Polyline(item.hDC, points.as_ptr(), 3);
        SelectObject(item.hDC, old);
        DeleteObject(pen as _);
    }
}
unsafe extern "system" fn procedure(window: HWND, msg: u32, w: WPARAM, l: LPARAM) -> LRESULT {
    match msg {
        WM_CREATE => {
            SetWindowLongPtrW(
                window,
                GWLP_USERDATA,
                Box::into_raw(Box::new(Visual::new(fit_dpi(
                    GetDpiForWindow(window),
                    window,
                )))) as _,
            );
            controls(window);
            resize(window);
            let mut tray: NOTIFYICONDATAW = std::mem::zeroed();
            tray.cbSize = size_of::<NOTIFYICONDATAW>() as _;
            tray.hWnd = window;
            tray.uID = 1;
            tray.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
            tray.uCallbackMessage = TRAY;
            tray.hIcon = visual(window).theme_icon;
            let tip = wide("Air Remote");
            tray.szTip[..tip.len()].copy_from_slice(&tip);
            Shell_NotifyIconW(NIM_ADD, &tray);
            SetTimer(window, 1, 500, None);
            0
        }
        WM_ERASEBKGND => 1,
        WM_PAINT => {
            paint(window);
            0
        }
        WM_DRAWITEM => {
            draw_button(window, &*(l as *const DRAWITEMSTRUCT));
            1
        }
        WM_CTLCOLORLISTBOX | WM_CTLCOLOREDIT | WM_CTLCOLORSTATIC => {
            let v = visual(window);
            SetTextColor(w as HDC, v.text);
            SetBkColor(w as HDC, v.card);
            v.card_brush as _
        }
        WM_SETTINGCHANGE | WM_THEMECHANGED | WM_DWMCOLORIZATIONCOLORCHANGED | WM_DPICHANGED => {
            if GetWindowLongPtrW(window, GWLP_USERDATA) == 0 {
                return DefWindowProcW(window, msg, w, l);
            }
            let old = GetWindowLongPtrW(window, GWLP_USERDATA) as *mut Visual;
            let dpi = if msg == WM_DPICHANGED {
                (w & 0xffff) as u32
            } else {
                GetDpiForWindow(window)
            };
            SetWindowLongPtrW(
                window,
                GWLP_USERDATA,
                Box::into_raw(Box::new(Visual::new(fit_dpi(dpi, window)))) as _,
            );
            let current = &mut *(GetWindowLongPtrW(window, GWLP_USERDATA) as *mut Visual);
            current.show_pairing = (*old).show_pairing;
            current.connections = (*old).connections;
            layout(window);
            let mut tray: NOTIFYICONDATAW = std::mem::zeroed();
            tray.cbSize = size_of::<NOTIFYICONDATAW>() as _;
            tray.hWnd = window;
            tray.uID = 1;
            tray.uFlags = NIF_ICON;
            tray.hIcon = current.theme_icon;
            Shell_NotifyIconW(NIM_MODIFY, &tray);
            drop(Box::from_raw(old));
            if msg == WM_DPICHANGED {
                let r = &*(l as *const RECT);
                SetWindowPos(
                    window,
                    null_mut(),
                    r.left,
                    r.top,
                    r.right - r.left,
                    r.bottom - r.top,
                    SWP_NOZORDER | SWP_NOACTIVATE,
                );
            }
            RedrawWindow(window, null(), null_mut(), RDW_INVALIDATE | RDW_ALLCHILDREN);
            0
        }
        WM_COMMAND => {
            match (w & 0xffff) as i32 {
                COPY => copy_code(window),
                PAIR => {
                    let v = &mut *(GetWindowLongPtrW(window, GWLP_USERDATA) as *mut Visual);
                    v.show_pairing = !v.show_pairing;
                    resize(window);
                }
                EXIT => {
                    DestroyWindow(window);
                }
                STARTUP => {
                    if let Err(e) =
                        crate::windows_startup::set_enabled(!crate::windows_startup::enabled())
                    {
                        error(&e.to_string());
                    }
                    InvalidateRect(GetDlgItem(window, STARTUP), null(), 0);
                }
                SHOW => {
                    ShowWindow(window, SW_RESTORE);
                    SetForegroundWindow(window);
                }
                BLUETOOTH => open(window, "ms-settings:bluetooth"),
                SOUND => open(window, "ms-settings:sound"),
                CABLE => {
                    let path = std::env::var("ProgramFiles")
                        .unwrap_or_else(|_| "C:\\Program Files".into());
                    let path = format!("{path}\\VB\\CABLE\\VBCABLE_ControlPanel.exe");
                    if std::path::Path::new(&path).exists() {
                        open(window, &path);
                    } else {
                        error("Компонент микрофона не установлен");
                    }
                }
                OUTPUT => {
                    let app = APP.get().unwrap();
                    let menu = CreatePopupMenu();
                    let selected = app.output.lock().unwrap().clone();
                    for (i, name) in app.outputs.iter().enumerate() {
                        AppendMenuW(
                            menu,
                            MF_STRING | if *name == selected { MF_CHECKED } else { 0 },
                            2000 + i,
                            wide(name).as_ptr(),
                        );
                    }
                    let mut rect: RECT = std::mem::zeroed();
                    GetWindowRect(GetDlgItem(window, OUTPUT), &mut rect);
                    let id = TrackPopupMenu(
                        menu,
                        TPM_RETURNCMD | TPM_LEFTALIGN,
                        rect.left,
                        rect.bottom,
                        0,
                        window,
                        null(),
                    );
                    DestroyMenu(menu);
                    if id >= 2000 {
                        if let Some(name) = app.outputs.get((id - 2000) as usize) {
                            if let Err(e) = app.select_output(name.clone()) {
                                app.status(e.to_string());
                            } else {
                                SetWindowTextW(GetDlgItem(window, OUTPUT), wide(name).as_ptr());
                            }
                        }
                    }
                }
                _ => {}
            }
            0
        }
        WM_TIMER => {
            if w == 2 {
                SetWindowTextW(GetDlgItem(window, COPY), wide("Скопировать код").as_ptr());
                KillTimer(window, 2);
            } else {
                let status = APP.get().unwrap().status.lock().unwrap().clone();
                let v = &mut *(GetWindowLongPtrW(window, GWLP_USERDATA) as *mut Visual);
                let connections = APP.get().unwrap().phone_connections();
                let collapse = connections > v.connections && v.show_pairing;
                v.connections = connections;
                if collapse {
                    v.show_pairing = false;
                }
                if v.status != status {
                    v.status = status;
                    InvalidateRect(window, null(), 0);
                }
                if collapse {
                    resize(window);
                }
            }
            0
        }
        WM_CLOSE => {
            ShowWindow(window, SW_HIDE);
            0
        }
        TRAY => {
            if l as u32 == WM_LBUTTONUP {
                ShowWindow(window, SW_RESTORE);
                SetForegroundWindow(window);
            } else if l as u32 == WM_RBUTTONUP || l as u32 == WM_CONTEXTMENU {
                let menu = CreatePopupMenu();
                let connected = APP.get().unwrap().phone_connections() > 0;
                AppendMenuW(
                    menu,
                    MF_STRING | MF_GRAYED,
                    0,
                    wide(if connected {
                        "Телефон подключён"
                    } else {
                        "Нет подключения к телефону"
                    })
                    .as_ptr(),
                );
                AppendMenuW(menu, MF_SEPARATOR, 0, null());
                AppendMenuW(
                    menu,
                    MF_STRING,
                    SHOW as usize,
                    wide("Открыть Air Remote").as_ptr(),
                );
                AppendMenuW(
                    menu,
                    MF_STRING
                        | if crate::windows_startup::enabled() {
                            MF_CHECKED
                        } else {
                            0
                        },
                    STARTUP as usize,
                    wide("Запускать с Windows").as_ptr(),
                );
                AppendMenuW(menu, MF_SEPARATOR, 0, null());
                AppendMenuW(
                    menu,
                    MF_STRING,
                    EXIT as usize,
                    wide("Завершить работу").as_ptr(),
                );
                let mut cursor: POINT = std::mem::zeroed();
                GetCursorPos(&mut cursor);
                SetForegroundWindow(window);
                let command = TrackPopupMenu(
                    menu,
                    TPM_RETURNCMD | TPM_RIGHTBUTTON,
                    cursor.x,
                    cursor.y,
                    0,
                    window,
                    null(),
                );
                DestroyMenu(menu);
                PostMessageW(window, WM_NULL, 0, 0);
                if command != 0 {
                    SendMessageW(window, WM_COMMAND, command as usize, 0);
                }
            }
            0
        }
        WM_DESTROY => {
            let mut tray: NOTIFYICONDATAW = std::mem::zeroed();
            tray.cbSize = size_of::<NOTIFYICONDATAW>() as _;
            tray.hWnd = window;
            tray.uID = 1;
            Shell_NotifyIconW(NIM_DELETE, &tray);
            PostQuitMessage(0);
            0
        }
        WM_NCDESTROY => {
            let ptr = SetWindowLongPtrW(window, GWLP_USERDATA, 0) as *mut Visual;
            if !ptr.is_null() {
                drop(Box::from_raw(ptr));
            }
            DefWindowProcW(window, msg, w, l)
        }
        _ => DefWindowProcW(window, msg, w, l),
    }
}
pub fn run(app: Arc<App>) -> Result<()> {
    APP.set(app).ok();
    unsafe {
        SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
        let instance = GetModuleHandleW(null());
        let class = wide("AirRemoteCompanion");
        let mut wc: WNDCLASSW = std::mem::zeroed();
        wc.lpfnWndProc = Some(procedure);
        wc.hInstance = instance;
        wc.lpszClassName = class.as_ptr();
        wc.hCursor = LoadCursorW(null_mut(), IDC_ARROW);
        wc.hIcon = icon();
        if RegisterClassW(&wc) == 0 {
            bail!("Cannot register window");
        }
        let style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX | WS_CLIPCHILDREN;
        let dpi = fit_dpi(GetDpiForSystem(), null_mut());
        let mut rect = RECT {
            left: 0,
            top: 0,
            right: (560 * dpi as i32 / 96),
            bottom: (600 * dpi as i32 / 96),
        };
        AdjustWindowRectExForDpi(&mut rect, style, 0, 0, dpi);
        let mut work: RECT = std::mem::zeroed();
        SystemParametersInfoW(SPI_GETWORKAREA, 0, &mut work as *mut _ as _, 0);
        let x = work.left + ((work.right - work.left - (rect.right - rect.left)) / 2).max(0);
        let y = work.top + ((work.bottom - work.top - (rect.bottom - rect.top)) / 2).max(0);
        let window = CreateWindowExW(
            WS_EX_CONTROLPARENT,
            class.as_ptr(),
            wide("Air Remote").as_ptr(),
            style,
            x,
            y,
            rect.right - rect.left,
            rect.bottom - rect.top,
            null_mut(),
            null_mut(),
            instance,
            null(),
        );
        if window.is_null() {
            bail!("Cannot create window");
        }
        if !std::env::args().any(|a| a == "--background") {
            ShowWindow(window, SW_SHOW);
        }
        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, null_mut(), 0, 0) > 0 {
            if IsDialogMessageW(window, &msg) == 0 {
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
    }
    Ok(())
}
