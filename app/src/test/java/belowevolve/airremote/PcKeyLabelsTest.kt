package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class PcKeyLabelsTest {
    @Test fun shiftReflectsTheSelectedLayout() {
        assertEquals("Q", pcKeyLabel("q", false, true, false))
        assertEquals("Й", pcKeyLabel("q", true, true, false))
        assertEquals("@", pcKeyLabel("2", false, true, false))
        assertEquals("\"", pcKeyLabel("2", true, true, false))
        assertEquals("№", pcKeyLabel("3", true, true, false))
        assertEquals("?", pcKeyLabel("7", true, true, false))
        assertEquals(".", pcKeyLabel("/", true, false, false))
        assertEquals(",", pcKeyLabel("/", true, true, false))
        assertEquals("|", pcKeyLabel("\\", false, true, false))
        assertEquals("/", pcKeyLabel("\\", true, true, false))
    }
    @Test fun capsChangesLettersAndShiftReversesCaps() {
        assertEquals("Й", pcKeyLabel("q", true, false, true))
        assertEquals("й", pcKeyLabel("q", true, true, true))
        assertEquals("2", pcKeyLabel("2", true, false, true))
        assertEquals("F1", pcKeyLabel("F1", true, true, true))
    }
}
