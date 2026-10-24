package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class DpadGestureTest {
    private fun gesture(key: Int = 23) = DpadGesture(key, 100f, 100f, 8f, 32f)

    @Test fun aTapToleratesSmallFingerMovement() {
        val gesture = gesture(19)
        assertNull(gesture.move(104f, 103f))
        assertFalse(gesture.moving)
        assertEquals(19, gesture.tap())
    }

    @Test fun aSwipeFromOkDoesNotAlsoClickOk() {
        val gesture = gesture()
        assertEquals(22, gesture.move(140f, 100f))
        assertTrue(gesture.moving)
        assertNull(gesture.tap())
    }

    @Test fun directionDependsOnMovementRatherThanStartingSector() {
        val gesture = gesture(21)
        assertEquals(22, gesture.move(140f, 100f))
        assertEquals(19, gesture.move(140f, 60f))
        assertEquals(21, gesture.move(100f, 60f))
        assertEquals(20, gesture.move(100f, 100f))
        assertNull(gesture.tap())
    }

    @Test fun jitterAndReturningToOriginDoNotProduceAnAccidentalTap() {
        val gesture = gesture()
        assertNull(gesture.move(115f, 100f))
        assertNull(gesture.move(100f, 100f))
        assertTrue(gesture.moving)
        assertNull(gesture.tap())
    }

    @Test fun continuousMovementRequiresAnotherStepBeforeRepeating() {
        val gesture = gesture()
        assertEquals(22, gesture.move(132f, 100f))
        assertNull(gesture.move(140f, 100f))
        assertEquals(22, gesture.move(164f, 110f))
    }
}
