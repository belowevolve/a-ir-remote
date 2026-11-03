package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class DpadRepeatTest {
    private fun repeat() = DpadRepeat(8f)

    @Test fun arrowsStartAt200msAndRepeatEvery75ms() {
        val repeat = repeat()
        repeat.update(22, 100f, 100f, 0)
        assertNull(repeat.poll(199))
        assertEquals(22, repeat.poll(200))
        assertNull(repeat.poll(274))
        assertEquals(22, repeat.poll(275))
    }

    @Test fun smallJitterDoesNotDelayAHoldButCumulativeDriftDoes() {
        val repeat = repeat()
        repeat.update(19, 100f, 100f, 0)
        repeat.update(19, 104f, 103f, 80)
        repeat.update(19, 107f, 100f, 150)
        assertEquals(19, repeat.poll(200))
        repeat.update(19, 109f, 100f, 550)
        assertNull(repeat.poll(749))
        assertEquals(19, repeat.poll(750))
    }

    @Test fun continuedSwipingRestartsTheHoldDelay() {
        val repeat = repeat()
        repeat.update(22, 100f, 100f, 0)
        repeat.update(22, 132f, 100f, 400, restart = true)
        assertNull(repeat.poll(599))
        assertEquals(22, repeat.poll(600))
    }

    @Test fun leavingAnArrowStopsRepeatsAndReturningRequiresAFreshPause() {
        val repeat = repeat()
        repeat.update(19, 100f, 50f, 0)
        assertEquals(19, repeat.poll(200))
        repeat.update(null, 100f, 100f, 600)
        assertNull(repeat.deadline)
        assertNull(repeat.poll(2000))
        repeat.update(19, 100f, 50f, 2100)
        assertNull(repeat.poll(2299))
        assertEquals(19, repeat.poll(2300))
    }

    @Test fun crossingASectorBoundaryRestartsDelayEvenWithSmallMovement() {
        val repeat = repeat()
        repeat.update(19, 100f, 100f, 0)
        repeat.update(22, 101f, 100f, 490)
        assertNull(repeat.poll(689))
        assertEquals(22, repeat.poll(690))
    }

    @Test fun aStalledUiNeverReplaysMissedRepeats() {
        val repeat = repeat()
        repeat.update(22, 100f, 100f, 0)
        assertEquals(22, repeat.poll(2000))
        assertNull(repeat.poll(2000))
        assertNull(repeat.poll(2074))
        assertEquals(22, repeat.poll(2075))
    }

    @Test fun okKeepsItsLongPressDelayWhileArrowsIgnoreTheSystemDelay() {
        val repeat = DpadRepeat(8f, okHoldDelayMillis = 1000)
        repeat.update(23, 100f, 100f, 0)
        assertNull(repeat.poll(200))
        assertNull(repeat.poll(999))
        assertEquals(23, repeat.poll(1000))
        repeat.update(22, 100f, 100f, 1100)
        assertNull(repeat.poll(1299))
        assertEquals(22, repeat.poll(1300))
    }

    @Test fun aSwipeOnlyArmsItsMatchingArrowAndNeverOk() {
        for (direction in listOf(19, 20, 21, 22)) {
            val gesture = DpadGesture(23, 0f, 0f, 8f, 32f)
            val x = when (direction) { 21 -> -80f; 22 -> 80f; else -> 0f }
            val y = when (direction) { 19 -> -80f; 20 -> 80f; else -> 0f }
            assertEquals(direction, gesture.move(x, y))
            val repeat = repeat()
            val region = dpadKey(x, y, 160f, 42f)
            repeat.update(region.takeIf { it == gesture.direction }, x, y, 0)
            assertEquals(direction, repeat.poll(200))
            assertNull(gesture.tap())
            val centre = dpadKey(0f, 0f, 160f, 42f)
            repeat.update(centre.takeIf { it == gesture.direction }, 0f, 0f, 600)
            assertNull(repeat.poll(1100))
        }
    }
}
