package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class TrackpadGesturesTest {
    private class Pad {
        val events = mutableListOf<String>()
        val gestures = TrackpadGestures(8f, 40f, 300, 500,
            move = { x, y -> events.add("move:$x,$y") },
            scroll = { events.add("scroll:$it") },
            click = { events.add(if (it) "right" else "click") },
            button = { events.add(if (it) "down" else "up") },
        )
        fun frame(time: Long, x: Float = 0f, y: Float = 0f, pressed: Boolean = true) =
            gestures.frame(time, listOf(TrackpadGestures.Contact(1, x, y, pressed)))
        fun tap(time: Long = 0) { frame(time); frame(time + 40, pressed = false) }
    }

    @Test fun singleTapClicksImmediatelyAndJitterDoesNotMoveCursor() {
        val p = Pad()
        p.frame(0); p.frame(20, 2f, 1f); p.frame(40, 1f, 0f, false)
        assertEquals(listOf("click"), p.events)
    }

    @Test fun secondTapPressesBeforeMovementAndReleasesAfterFinalMovement() {
        val p = Pad()
        p.tap(); p.frame(100); p.frame(120, 30f); p.frame(140, 35f, pressed = false)
        assertEquals(listOf("click", "down", "move:30.0,0.0", "move:5.0,0.0", "up"), p.events)
    }

    @Test fun doubleTapHasExactlyTwoClicks() {
        val p = Pad()
        p.tap(); p.tap(100)
        assertEquals(listOf("click", "down", "up"), p.events)
    }

    @Test fun lateOrDistantSecondTouchOnlyMovesPointer() {
        for ((time, x) in listOf(400L to 0f, 100L to 100f)) {
            val p = Pad()
            p.tap(); p.frame(time, x); p.frame(time + 20, x + 30); p.frame(time + 40, x + 30, pressed = false)
            assertEquals(listOf("click", "move:30.0,0.0", "move:0.0,0.0"), p.events)
        }
    }

    @Test fun stationaryLongPressDragsAndNeverClicksOnRelease() {
        val p = Pad()
        p.frame(0)
        assertEquals(500L, p.gestures.holdDelay(0))
        p.gestures.hold()
        assertNull(p.gestures.holdDelay(500))
        p.frame(520, 20f); p.frame(540, 20f, pressed = false)
        assertEquals(listOf("down", "move:20.0,0.0", "move:0.0,0.0", "up"), p.events)
    }

    @Test fun twoFingerTapRightClicksEvenWhenFingersLiftSeparately() {
        val p = Pad()
        p.frame(0)
        p.gestures.frame(10, listOf(TrackpadGestures.Contact(1, 0f, 0f), TrackpadGestures.Contact(2, 80f, 0f)))
        p.gestures.frame(30, listOf(TrackpadGestures.Contact(1, 0f, 0f, false), TrackpadGestures.Contact(2, 80f, 0f)))
        p.gestures.frame(40, listOf(TrackpadGestures.Contact(2, 80f, 0f, false)))
        assertEquals(listOf("right"), p.events)
    }

    @Test fun twoFingerScrollDoesNotMoveCursorOrClickWhenOneFingerRemains() {
        val p = Pad()
        p.gestures.frame(0, listOf(TrackpadGestures.Contact(1, 0f, 0f), TrackpadGestures.Contact(2, 80f, 0f)))
        p.gestures.frame(20, listOf(TrackpadGestures.Contact(1, 0f, 30f), TrackpadGestures.Contact(2, 80f, 30f)))
        p.gestures.frame(30, listOf(TrackpadGestures.Contact(1, 0f, 30f, false), TrackpadGestures.Contact(2, 80f, 30f)))
        p.gestures.frame(40, listOf(TrackpadGestures.Contact(2, 100f, 60f)))
        p.gestures.frame(50, listOf(TrackpadGestures.Contact(2, 100f, 60f, false)))
        assertEquals(listOf("scroll:30.0", "scroll:0.0"), p.events)
    }

    @Test fun cancellationReleasesButtonAndClearsTapHistory() {
        val p = Pad()
        p.tap(); p.frame(100); p.gestures.cancel(); p.frame(150)
        assertEquals(listOf("click", "down", "up"), p.events)
        assertNotNull(p.gestures.holdDelay(150))
    }

    @Test fun secondFingerReleasesDragBeforeScrolling() {
        val p = Pad()
        p.tap(); p.frame(100)
        p.gestures.frame(110, listOf(TrackpadGestures.Contact(1, 0f, 0f), TrackpadGestures.Contact(2, 80f, 0f)))
        p.gestures.frame(130, listOf(TrackpadGestures.Contact(1, 0f, 30f), TrackpadGestures.Contact(2, 80f, 30f)))
        assertEquals(listOf("click", "down", "up", "scroll:30.0"), p.events)
    }
}
