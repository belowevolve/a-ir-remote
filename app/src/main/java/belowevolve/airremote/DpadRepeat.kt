package belowevolve.airremote

import kotlin.math.abs
import kotlin.math.max

/** A stable finger arms a hold. Meaningful motion always starts a fresh delay. */
internal class DpadRepeat(
    private val touchSlop: Float,
    private val holdDelayMillis: Long = 200,
    private val intervalMillis: Long = 75,
    private val okHoldDelayMillis: Long = 500,
) {
    private var key: Int? = null
    private var anchorX = 0f
    private var anchorY = 0f
    var deadline: Long? = null
        private set

    fun update(nextKey: Int?, x: Float, y: Float, now: Long, restart: Boolean = false) {
        val moved = max(abs(x - anchorX), abs(y - anchorY)) > touchSlop
        if (nextKey == null || nextKey != key || moved || restart) {
            key = nextKey
            anchorX = x
            anchorY = y
            deadline = nextKey?.let { now + if (it == 23) okHoldDelayMillis else holdDelayMillis }
        }
    }

    fun poll(now: Long): Int? {
        val due = deadline ?: return null
        if (now < due) return null
        // Schedule from actual time: a stalled UI must never catch up in a burst.
        deadline = now + intervalMillis
        return key
    }
}
