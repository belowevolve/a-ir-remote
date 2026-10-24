package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class DpadHitTestTest {
    @Test fun sectorsCoverTheSpaceBetweenIcons() {
        assertEquals(19, dpadKey(60f, -90f, 160f, 42f))
        assertEquals(20, dpadKey(-60f, 90f, 160f, 42f))
        assertEquals(21, dpadKey(-100f, 50f, 160f, 42f))
        assertEquals(22, dpadKey(100f, -50f, 160f, 42f))
        assertEquals(23, dpadKey(20f, 20f, 160f, 42f))
        assertNull(dpadKey(150f, 150f, 160f, 42f))
        assertNull(dpadKey(0f, 0f, 0f, 42f))
    }
}
