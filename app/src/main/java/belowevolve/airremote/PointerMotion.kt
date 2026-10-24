package belowevolve.airremote

/** Keep subpixel motion across touch samples instead of losing slow movements. */
internal class PointerMotion {
    private var remainderX = 0f
    private var remainderY = 0f

    fun move(dx: Float, dy: Float, scale: Float, send: (Int, Int) -> Unit) {
        remainderX += dx * scale
        remainderY += dy * scale
        val x = remainderX.toInt()
        val y = remainderY.toInt()
        if (x != 0 || y != 0) {
            send(x, y)
            remainderX -= x
            remainderY -= y
        }
    }
}

internal object PcPointerSensitivity {
    val Range = 0.6f..8f
    fun normalize(value: Float) = if (value.isFinite()) value.coerceIn(Range) else 1.6f
}
