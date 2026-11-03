package belowevolve.airremote

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS

/** Exercise Compose pointer timeouts with real Android events, without sending TV commands. */
internal fun Instrumentation.checkDpadInput() {
    val keys = LinkedBlockingQueue<Int>()
    val holds = LinkedBlockingQueue<Boolean>()
    val laidOut = LinkedBlockingQueue<Rect>()
    val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    var downTime = 0L
    var x = 0f
    var y = 0f
    fun pointer(action: Int, nextX: Float = x, nextY: Float = y) {
        x = nextX
        y = nextY
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        try { sendPointerSync(event) } finally { event.recycle() }
        waitForIdleSync()
    }
    fun expectKey(key: Int, timeout: Long = 100) {
        check(keys.poll(timeout, MILLISECONDS) == key) { "Expected D-pad key $key" }
    }
    fun expectQuiet(timeout: Long = 250) {
        check(keys.poll(timeout, MILLISECONDS) == null) { "Unexpected D-pad step" }
        check(holds.isEmpty()) { "Unexpected OK hold" }
    }
    try {
        runOnMainSync {
            activity.setContent {
                RemoteTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TvDpad(true, { keys.offer(it) }, { holds.offer(it) },
                            Modifier.size(320.dp).onGloballyPositioned { laidOut.offer(it.boundsInWindow()) })
                    }
                }
            }
        }
        val bounds = checkNotNull(laidOut.poll(3000, MILLISECONDS)) { "D-pad did not lay out" }
        val cx = bounds.center.x
        val cy = bounds.center.y
        val step = bounds.width * 0.25f

        // A quick swipe remains one step, including drift on the release event.
        pointer(MotionEvent.ACTION_DOWN, cx, cy)
        pointer(MotionEvent.ACTION_MOVE, cx + step, cy)
        expectKey(22)
        pointer(MotionEvent.ACTION_UP, cx + step * 1.6f, cy)
        expectQuiet()

        // Pause after a swipe: no immediate repeat, then steady repeats, then stop on release.
        pointer(MotionEvent.ACTION_DOWN, cx, cy)
        pointer(MotionEvent.ACTION_MOVE, cx + step, cy)
        expectKey(22)
        expectQuiet(100)
        expectKey(22, 250)
        expectKey(22, 150)
        pointer(MotionEvent.ACTION_UP)
        expectQuiet()

        // After auto-repeat, sliding back to the centre stops without a reverse step.
        pointer(MotionEvent.ACTION_DOWN, cx, cy)
        pointer(MotionEvent.ACTION_MOVE, cx + step, cy)
        expectKey(22)
        expectKey(22, 900)
        pointer(MotionEvent.ACTION_MOVE, cx + step * 0.6f, cy)
        pointer(MotionEvent.ACTION_MOVE, cx + step * 0.2f, cy)
        pointer(MotionEvent.ACTION_MOVE, cx, cy)
        check(keys.poll(700, MILLISECONDS) == null)
        check(holds.isEmpty())
        pointer(MotionEvent.ACTION_UP)
        expectQuiet()

        // Starting in an arrow also supports hold, with no extra tap on release.
        pointer(MotionEvent.ACTION_DOWN, cx, cy - step)
        expectKey(19, 900)
        pointer(MotionEvent.ACTION_UP)
        expectQuiet()

        // Returning to OK while moving must not arm OK or a different arrow.
        pointer(MotionEvent.ACTION_DOWN, cx, cy)
        pointer(MotionEvent.ACTION_MOVE, cx + step, cy)
        expectKey(22)
        pointer(MotionEvent.ACTION_MOVE, cx, cy)
        expectKey(21)
        check(keys.poll(700, MILLISECONDS) == null)
        check(holds.isEmpty())
        pointer(MotionEvent.ACTION_UP)
        expectQuiet()

        // Leaving the circle cancels a hold immediately, even while the finger stays down.
        pointer(MotionEvent.ACTION_DOWN, cx, cy)
        pointer(MotionEvent.ACTION_MOVE, cx + step, cy)
        expectKey(22)
        expectKey(22, 900)
        pointer(MotionEvent.ACTION_MOVE, bounds.right + 8, cy)
        check(keys.poll(700, MILLISECONDS) == null)
        pointer(MotionEvent.ACTION_UP)
        expectQuiet()

        // OK remains a paired long-press, and Android cancellation always releases it.
        pointer(MotionEvent.ACTION_DOWN, cx, cy)
        check(holds.poll(900, MILLISECONDS) == true)
        pointer(MotionEvent.ACTION_CANCEL)
        check(holds.poll(300, MILLISECONDS) == false)
        expectQuiet()

        // The power control must distinguish a short click from paired hold edges.
        laidOut.clear()
        runOnMainSync {
            activity.setContent {
                RemoteTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        RemoteButton(Icons.Rounded.PowerSettingsNew, "Питание",
                            Modifier.size(64.dp).onGloballyPositioned { laidOut.offer(it.boundsInWindow()) },
                            onHold = { holds.offer(it) }, onClick = { keys.offer(26) })
                    }
                }
            }
        }
        val power = checkNotNull(laidOut.poll(3000, MILLISECONDS))
        pointer(MotionEvent.ACTION_DOWN, power.center.x, power.center.y)
        pointer(MotionEvent.ACTION_UP)
        expectKey(26)
        expectQuiet()

        pointer(MotionEvent.ACTION_DOWN, power.center.x, power.center.y)
        check(holds.poll(900, MILLISECONDS) == true) { "Power hold was not started" }
        pointer(MotionEvent.ACTION_UP)
        check(holds.poll(300, MILLISECONDS) == false) { "Power hold was not released" }
        expectQuiet()

        pointer(MotionEvent.ACTION_DOWN, power.center.x, power.center.y)
        check(holds.poll(900, MILLISECONDS) == true)
        pointer(MotionEvent.ACTION_CANCEL)
        check(holds.poll(300, MILLISECONDS) == false) { "Power remained held after cancellation" }
        expectQuiet()
    } finally {
        runOnMainSync { activity.finish() }
    }
}
