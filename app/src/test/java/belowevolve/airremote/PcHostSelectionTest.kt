package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class PcHostSelectionTest {
    @Test fun forgettingClearsSelectionAndRejectsLateReconnects() {
        val selection = PcHostSelection("A", true)
        selection.forget()
        assertNull(selection.address)
        assertFalse(selection.allowed)
        assertFalse(selection.accepts("A"))
        assertFalse(selection.accepts("B"))
        val restored = PcHostSelection(selection.address, selection.allowed)
        assertFalse(restored.accepts("A"))
        restored.select("A")
        assertTrue(restored.accepts("A"))
    }

    @Test fun manualDisconnectRejectsReconnectUntilUserSelectsHost() {
        val selection = PcHostSelection("A", true)
        selection.disconnect()
        assertFalse(selection.accepts("A"))
        assertFalse(selection.accepts("B"))
        selection.select("B")
        assertFalse(selection.accepts("A"))
        assertTrue(selection.accepts("B"))
    }

    @Test fun switchingRejectsLateEventsFromOldHost() {
        val selection = PcHostSelection("A", true)
        selection.select("B")
        assertFalse(selection.accepts("A"))
        assertTrue(selection.accepts("B"))
        selection.select("A")
        assertTrue(selection.accepts("A"))
        assertFalse(selection.accepts("B"))
    }

    @Test fun disconnectedPreferenceSurvivesModelRecreation() {
        val selection = PcHostSelection("A", false)
        assertFalse(selection.accepts("A"))
        selection.select("A")
        assertTrue(selection.accepts("A"))
    }

    @Test fun pairingAcceptsNewHostButRejectsPreviousHost() {
        val selection = PcHostSelection("A", true)
        selection.disconnect()
        selection.pair()
        assertFalse(selection.accepts("A"))
        assertTrue(selection.accepts("B"))
        selection.select("B")
        assertFalse(selection.accepts("C"))
    }

    @Test fun firstPairingAcceptsIncomingConnection() {
        assertTrue(PcHostSelection(null, true).accepts("A"))
    }
}
