package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class PointerMotionTest {
    @Test fun slowMotionAccumulatesInBothDirections() {
        val reports = mutableListOf<Pair<Int, Int>>()
        val motion = PointerMotion()
        repeat(8) { motion.move(0.25f, -0.5f, 0.5f) { x, y -> reports += x to y } }
        assertEquals(1, reports.sumOf { it.first })
        assertEquals(-2, reports.sumOf { it.second })
    }

    @Test fun aNewGestureDoesNotInheritPreviousRemainders() {
        val reports = mutableListOf<Pair<Int, Int>>()
        repeat(2) { PointerMotion().move(0.75f, 0f, 1f) { x, y -> reports += x to y } }
        assertTrue(reports.isEmpty())
    }
    @Test fun sensitivitySupportsFastMovementAndRejectsInvalidStoredValues() {
        assertEquals(8f, PcPointerSensitivity.normalize(12f), 0f)
        assertEquals(6f, PcPointerSensitivity.normalize(6f), 0f)
        assertEquals(0.6f, PcPointerSensitivity.normalize(-1f), 0f)
        assertEquals(1.6f, PcPointerSensitivity.normalize(Float.NaN), 0f)
        assertEquals(1.6f, PcPointerSensitivity.normalize(Float.POSITIVE_INFINITY), 0f)
    }

}
