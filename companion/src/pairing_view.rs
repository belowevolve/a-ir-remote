//! Pairing follows connection edges, leaving manual QR toggles alone between edges.
#[derive(Clone, Copy)]
pub struct PairingView {
    pub expanded: bool,
    connections: usize,
    active: bool,
}

impl PairingView {
    pub fn new(connections: usize) -> Self {
        Self {
            expanded: connections == 0,
            connections,
            active: false,
        }
    }

    pub fn sync(&mut self, connections: usize) -> bool {
        let before = (self.expanded, self.connected());
        if (self.connections > 0) != (connections > 0) {
            self.expanded = connections == 0;
        }
        self.connections = connections;
        before != (self.expanded, self.connected())
    }

    pub fn connected(&self) -> bool {
        self.connections > 0
    }

    pub fn set_active(&mut self, active: bool, connections: usize) -> bool {
        if !active {
            self.active = false;
            return false;
        }
        let before = (self.expanded, self.connected());
        self.sync(connections);
        if active && !self.active && connections == 0 {
            self.expanded = true;
        }
        self.active = active;
        before != (self.expanded, self.connected())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn final_disconnect_restores_qr() {
        let mut view = PairingView::new(0);
        assert!(view.expanded);
        assert!(view.sync(1));
        assert!(!view.expanded);
        assert!(view.sync(0));
        assert!(view.expanded);
    }

    #[test]
    fn connection_handover_preserves_manual_qr() {
        let mut view = PairingView::new(1);
        view.expanded = true;
        assert!(!view.sync(2));
        assert!(!view.sync(1));
        assert!(view.expanded);
    }

    #[test]
    fn audio_still_connected_does_not_reopen_qr() {
        let mut view = PairingView::new(2);
        assert!(!view.sync(1));
        assert!(!view.expanded);
        assert!(view.sync(0));
    }

    #[test]
    fn restore_without_phone_reopens_qr_even_if_connection_was_missed() {
        let mut view = PairingView::new(0);
        view.set_active(true, 0);
        view.expanded = false;
        assert!(!view.set_active(true, 0)); // A resize must preserve the manual page.
        assert!(!view.set_active(false, 0));
        assert!(view.set_active(true, 0));
    }

    #[test]
    fn restore_while_connected_preserves_manual_page() {
        let mut view = PairingView::new(1);
        view.expanded = true;
        view.set_active(false, 1);
        assert!(!view.set_active(true, 1));
        assert!(view.expanded);
    }

    #[test]
    fn hidden_disconnect_is_applied_on_restore() {
        let mut view = PairingView::new(1);
        view.set_active(true, 1);
        assert!(!view.set_active(false, 0));
        assert!(view.set_active(true, 0));
        assert!(view.expanded);
    }

    #[test]
    fn connecting_from_manual_dashboard_updates_its_layout() {
        let mut view = PairingView::new(0);
        view.expanded = false;
        assert!(view.sync(1));
        assert!(view.connected());
        assert!(!view.expanded);
    }
}
