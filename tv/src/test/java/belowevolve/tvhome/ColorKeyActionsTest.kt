package belowevolve.tvhome

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorKeyActionsTest {
    @Test fun lateStoppedBroadcastDoesNotReportFailureOverTheVisiblePlayer() {
        val host = Host(false)
        val keys = ColorKeyActions(host)
        keys.dreamStarted()
        keys.key("mov.film", true, 0)
        host.timeout!!.run()
        assertEquals(0, host.failures)
        keys.dreamStopped()
        assertEquals(1, host.launches)
    }

    @Test fun onlyAnObservedRemainingDreamReportsFailure() {
        for (visible in listOf(true, false, null)) {
            val host = Host(visible)
            val keys = ColorKeyActions(host)
            keys.dreamStarted()
            keys.key("mov.film", true, 0)
            host.timeout!!.run()
            assertEquals(if (visible == true) 1 else 0, host.failures)
        }
    }

    @Test fun rejectedHomeActionWaitsForTheTargetLaunchBeforeReportingFailure() {
        val host = Host(false, accepted = false)
        val keys = ColorKeyActions(host)
        keys.dreamStarted()
        keys.key("mov.film", true, 0)
        assertEquals(1, host.launches)
        assertEquals(0, host.failures)
        host.timeout!!.run()
        assertEquals(0, host.failures)
    }

    private class Host(private val visible: Boolean?, private val accepted: Boolean = true) : ColorKeyActions.Host {
        var failures = 0
        var launches = 0
        var timeout: Runnable? = null
        override fun prepare(action: String) = object : ColorKeyActions.Target {
            override fun launch() { launches++ }
            override fun restoreIfNeeded() = Unit
        }
        override fun dismissDream() = accepted
        override fun isDreamVisible() = visible
        override fun scheduleTimeout(timeout: Runnable, delayMs: Long) { this.timeout = timeout }
        override fun cancelTimeout(timeout: Runnable) { this.timeout = null }
        override fun trace(event: String) = Unit
        override fun dismissalFailed() { failures++ }
    }
}
