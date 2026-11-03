package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class DpadRepeatTest {
    private fun repeat() = DpadRepeat(8f, 500)

    @Test fun aShortPauseDoesNotRepeatAndAHoldUsesAModeratePace() {
        val repeat = repeat()
        repeat.update(22, 100f, 100f, 0)
        assertNull(repeat.poll(499))
        assertEquals(22, repeat.poll(500))
        assertNull(repeat.poll(649))
        assertEquals(22, repeat.poll(650))
    }

    @Test fun smallJitterDoesNotDelayAHoldButCumulativeDriftDoes() {
        val repeat = repeat()
        repeat.update(19, 100f, 100f, 0)
        repeat.update(19, 104f, 103f, 200)
        repeat.update(19, 107f, 100f, 400)
        assertEquals(19, repeat.poll(500))
        repeat.update(19, 109f, 100f, 550)
        assertNull(repeat.poll(650))
        assertEquals(19, repeat.poll(1050))
    }

    @Test fun continuedSwipingRestartsTheHoldDelay() {
        val repeat = repeat()
        repeat.update(22, 100f, 100f, 0)
        repeat.update(22, 132f, 100f, 400, restart = true)
        assertNull(repeat.poll(500))
        assertEquals(22, repeat.poll(900))
    }

    @Test fun leavingAnArrowStopsRepeatsAndReturningRequiresAFreshPause() {
        val repeat = repeat()
        repeat.update(19, 100f, 50f, 0)
        assertEquals(19, repeat.poll(500))
        repeat.update(null, 100f, 100f, 600)
        assertNull(repeat.deadline)
        assertNull(repeat.poll(2000))
        repeat.update(19, 100f, 50f, 2100)
        assertNull(repeat.poll(2599))
        assertEquals(19, repeat.poll(2600))
    }

    @Test fun crossingASectorBoundaryRestartsDelayEvenWithSmallMovement() {
        val repeat = repeat()
        repeat.update(19, 100f, 100f, 0)
        repeat.update(22, 101f, 100f, 490)
        assertNull(repeat.poll(500))
        assertEquals(22, repeat.poll(990))
    }

    @Test fun aStalledUiNeverReplaysMissedRepeats() {
        val repeat = repeat()
        repeat.update(22, 100f, 100f, 0)
        assertEquals(22, repeat.poll(2000))
        assertNull(repeat.poll(2000))
        assertNull(repeat.poll(2149))
        assertEquals(22, repeat.poll(2150))
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
            assertEquals(direction, repeat.poll(500))
            assertNull(gesture.tap())
            val centre = dpadKey(0f, 0f, 160f, 42f)
            repeat.update(centre.takeIf { it == gesture.direction }, 0f, 0f, 600)
            assertNull(repeat.poll(1100))
        }
    }
}
