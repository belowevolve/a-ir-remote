package belowevolve.airremote

import kotlin.math.abs
import kotlin.math.max

/** Relative swipes work from any sector, including OK. Distances use the caller's units. */
internal class DpadGesture(
    private val tapKey: Int,
    private var anchorX: Float,
    private var anchorY: Float,
    private val touchSlop: Float,
    private val swipeDistance: Float,
) {
    var moving = false
        private set

    fun move(x: Float, y: Float): Int? {
        val dx = x - anchorX
        val dy = y - anchorY
        val distance = max(abs(dx), abs(dy))
        if (distance > touchSlop) moving = true
        if (distance < swipeDistance) return null
        anchorX = x
        anchorY = y
        return dpadDirection(dx, dy)
    }

    fun tap(): Int? = tapKey.takeUnless { moving }
}
