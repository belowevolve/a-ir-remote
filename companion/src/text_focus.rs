//! Event-driven editable focus metadata. Exists only while a phone opts in.
use std::sync::{Arc, Mutex};
use windows::{
    core::{implement, AgileReference, Interface, Ref},
    Win32::{
        System::Com::{
            CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER,
            COINIT_MULTITHREADED,
        },
        UI::Accessibility::{
            CUIAutomation8, IUIAutomation, IUIAutomation2, IUIAutomationElement,
            IUIAutomationFocusChangedEventHandler, IUIAutomationFocusChangedEventHandler_Impl,
            IUIAutomationValuePattern, UIA_DocumentControlTypeId, UIA_EditControlTypeId,
            UIA_ValuePatternId,
        },
    },
};
struct Apartment;
impl Drop for Apartment {
    fn drop(&mut self) {
        unsafe { CoUninitialize() }
    }
}
#[derive(Default)]
struct State {
    element: Option<AgileReference<IUIAutomationElement>>,
    serial: u64,
    editable: bool,
}
impl State {
    fn observe(&mut self, automation: &IUIAutomation, element: Option<&IUIAutomationElement>) {
        if let (Some(old), Some(new)) = (&self.element, element) {
            if let Ok(old) = old.resolve() {
                if unsafe { automation.CompareElements(&old, new) }.is_ok_and(|same| same.as_bool())
                {
                    return;
                }
            }
        }
        self.element = element.and_then(|field| AgileReference::new(field).ok());
        self.serial = self.serial.wrapping_add(1);
        self.editable = element.is_some_and(|field| unsafe {
            let value = field.GetCurrentPatternAs::<IUIAutomationValuePattern>(UIA_ValuePatternId);
            let writable = value
                .as_ref()
                .is_ok_and(|value| value.CurrentIsReadOnly().is_ok_and(|v| !v.as_bool()));
            let kind = field.CurrentControlType().ok();
            let rich_edit = kind == Some(UIA_DocumentControlTypeId)
                && (writable
                    || field.CurrentClassName().is_ok_and(|name| {
                        name.to_string()
                            .to_ascii_lowercase()
                            .starts_with("richedit")
                    }));
            (kind == Some(UIA_EditControlTypeId) || rich_edit)
                && field.CurrentHasKeyboardFocus().is_ok_and(|v| v.as_bool())
                && field.CurrentIsEnabled().is_ok_and(|v| v.as_bool())
                && value
                    .map(|value| value.CurrentIsReadOnly().is_ok_and(|v| !v.as_bool()))
                    .unwrap_or(true)
        });
    }
}
#[implement(IUIAutomationFocusChangedEventHandler)]
struct Handler {
    automation: IUIAutomation,
    state: Arc<Mutex<State>>,
}
impl IUIAutomationFocusChangedEventHandler_Impl for Handler_Impl {
    fn HandleFocusChangedEvent(
        &self,
        sender: Ref<IUIAutomationElement>,
    ) -> windows::core::Result<()> {
        if let Ok(mut state) = self.state.lock() {
            state.observe(&self.automation, sender.as_ref());
        }
        Ok(())
    }
}
pub struct Monitor {
    automation: IUIAutomation,
    handler: IUIAutomationFocusChangedEventHandler,
    state: Arc<Mutex<State>>,
    _apartment: Apartment,
}
impl Monitor {
    pub fn start() -> Option<Self> {
        unsafe {
            CoInitializeEx(None, COINIT_MULTITHREADED).ok().ok()?;
            let apartment = Apartment;
            let automation: IUIAutomation =
                CoCreateInstance(&CUIAutomation8, None, CLSCTX_INPROC_SERVER).ok()?;
            let timed: IUIAutomation2 = automation.cast().ok()?;
            timed.SetConnectionTimeout(300).ok()?;
            timed.SetTransactionTimeout(200).ok()?;
            let state = Arc::new(Mutex::new(State::default()));
            let handler: IUIAutomationFocusChangedEventHandler = Handler {
                automation: automation.clone(),
                state: state.clone(),
            }
            .into();
            automation
                .AddFocusChangedEventHandler(None, &handler)
                .ok()?;
            if let Ok(element) = automation.GetFocusedElement() {
                state.lock().ok()?.observe(&automation, Some(&element));
            }
            Some(Self {
                automation,
                handler,
                state,
                _apartment: apartment,
            })
        }
    }
    pub fn snapshot(&self) -> Option<(u64, bool)> {
        self.state.lock().ok().map(|s| (s.serial, s.editable))
    }
}
impl Drop for Monitor {
    fn drop(&mut self) {
        let _ = unsafe {
            self.automation
                .RemoveFocusChangedEventHandler(&self.handler)
        };
    }
}
