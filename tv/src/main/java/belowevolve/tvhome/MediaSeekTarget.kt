package belowevolve.tvhome

internal data class MediaSeekTarget(val beforeMs: Long, val targetMs: Long) {
    companion object {
        fun calculate(position: Long, updatedAt: Long, now: Long, speed: Float, playing: Boolean,
            duration: Long, seconds: Int): MediaSeekTarget {
            require(position >= 0 && seconds != 0 && seconds in -6000..6000)
            val elapsed = if (playing && updatedAt > 0 && speed.isFinite())
                (now - updatedAt).coerceAtLeast(0).toDouble() * speed else 0.0
            val maximum = if (duration > 0) duration.toDouble() else Long.MAX_VALUE.toDouble()
            val before = (position + elapsed).coerceIn(0.0, maximum).toLong()
            val target = (before.toDouble() + seconds * 1000L).coerceIn(0.0, maximum).toLong()
            return MediaSeekTarget(before, target)
        }
    }
}
