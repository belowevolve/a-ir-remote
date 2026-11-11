package belowevolve.tvhome

/** Time to act on the visible no-video message, excluding HDMI tuning/negotiation. */
internal class HdmiSignalTimeout(private val timeoutMs: Long = 10_000L) {
    private var resumed = false
    private var unavailableSince: Long? = null

    fun resume(now: Long, videoAvailable: Boolean, audioOnly: Boolean, messageVisible: Boolean = false) {
        resumed = true
        if (videoAvailable || audioOnly) available()
        else if (messageVisible) unavailable(now, false)
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
