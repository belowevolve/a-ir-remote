package belowevolve.tvhome

/** One continuous no-video interval. Repeated callbacks and reconnects never extend it. */
internal class HdmiSignalTimeout(private val timeoutMs: Long = 6_000L) {
    private var resumed = false
    private var unavailableSince: Long? = null

    fun resume(now: Long, videoAvailable: Boolean, audioOnly: Boolean) {
        resumed = true
        if (videoAvailable || audioOnly) available() else unavailable(now, false)
    }

    fun unavailable(now: Long, audioOnly: Boolean) {
        if (audioOnly) available()
        else if (resumed && unavailableSince == null) unavailableSince = now
    }

    fun available() {
        unavailableSince = null
    }

    fun pause() {
        resumed = false
        unavailableSince = null
    }

    fun remaining(now: Long): Long? =
        if (resumed) unavailableSince?.let { (timeoutMs - (now - it)).coerceAtLeast(0L) } else null
}
