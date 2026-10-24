package belowevolve.airremote

import kotlin.math.abs

/** Coordinates relative to the centre. Diagonals divide the four arrow sectors. */
internal fun dpadKey(x: Float, y: Float, radius: Float, centreRadius: Float): Int? {
    val distanceSquared = x * x + y * y
    if (radius <= 0 || distanceSquared > radius * radius) return null
    if (distanceSquared <= centreRadius * centreRadius) return 23
    return if (abs(y) >= abs(x)) { if (y < 0) 19 else 20 } else { if (x < 0) 21 else 22 }
}
