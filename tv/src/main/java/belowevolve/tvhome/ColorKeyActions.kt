package belowevolve.tvhome

/* Launches immediately; restores the same concrete target if dream dismissal brought HOME up. */
class ColorKeyActions(private val host: Host) {
    private var dreaming: Boolean = false
    private var pendingTarget: Target? = null
    private val timeout: Runnable = Runnable({ this.onTimeout() })

    interface Target {
        fun launch()

        fun restoreIfNeeded()
    }

    interface Host {
        fun prepare(action: String): Target?

        fun dismissDream(): Boolean

        fun scheduleTimeout(timeout: Runnable, delayMs: Long)

        fun cancelTimeout(timeout: Runnable)

        fun trace(event: String)

        fun dismissalFailed()
    }

    private fun onTimeout() {
        if (pendingTarget == null) return
        pendingTarget!!.restoreIfNeeded()
        val failedToClose: Boolean = dreaming
        // The STOPPED broadcast may have been lost. The service rechecks the current root package
        // on the next shortcut, so an old observation must not trap every later key behind HOME.
        dreaming = false
        cancelPending()
        if (failedToClose) {
            host.trace("dream_dismiss_timeout")
            host.dismissalFailed()
        }
    }

    fun dreamStarted() {
        dreaming = true
    }

    fun dreamStopped() {
        dreaming = false
        if (pendingTarget != null) {
            pendingTarget!!.restoreIfNeeded()
            // HOME injection is asynchronous. Retain a bounded visibility correction during
            // settling.
            host.cancelTimeout(timeout)
            host.scheduleTimeout(timeout, SETTLE_MS)
        }
    }

    fun homeShown() {
        if (pendingTarget != null) pendingTarget!!.restoreIfNeeded()
    }

    fun cancelCorrection() {
        if (pendingTarget != null) host.trace("dream_correction_cancelled")
        cancelPending()
    }

    /* The complete down/repeat/up sequence is consumed for a configured shortcut. */
    fun key(action: String?, down: Boolean, repeatCount: Int): Boolean {
        if (action == null || "none" == action) return false
        if (!down || repeatCount != 0) return true
        val target: Target? = host.prepare(action)
        if (target == null) return true
        if (!dreaming) {
            // A newer shortcut replaces any outstanding correction from the previous dream exit.
            if (pendingTarget != null) pendingTarget = target
            target.launch()
            return true
        }

        val alreadyDismissing: Boolean = pendingTarget != null
        // If the user changes their mind during the transition, only the latest target is restored.
        pendingTarget = target
        if (!alreadyDismissing) {
            host.scheduleTimeout(timeout, DISMISS_TIMEOUT_MS)
            if (!host.dismissDream()) {
                cancelPending()
                host.trace("dream_dismiss_rejected")
                host.dismissalFailed()
            }
        }
        // Starting the app and closing the screensaver happen together; never wait for STOPPED.
        target.launch()
        return true
    }

    fun reset() {
        dreaming = false
        cancelPending()
    }

    private fun cancelPending() {
        pendingTarget = null
        host.cancelTimeout(timeout)
    }

    companion object {
        val DISMISS_TIMEOUT_MS: Long = 2500
        val SETTLE_MS: Long = 250
    }
}
