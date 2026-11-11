package belowevolve.tvhome

import java.util.ArrayList

/** Pure shortcut regressions, also used by the JVM test suite. */
object ColorKeyActionsRegression {
    @JvmStatic
    fun main(args: Array<String>) {
        run()
        println(
            "Color shortcuts: immediate launch, dream/HOME races, concrete target, repeat, timeout and cancellation: PASS",
        )
    }

    fun run() {
        awakeAndRepeat()
        launchDuringDream()
        concreteTargetAndReplacement()
        timeoutAndCancellation()
    }

    private fun awakeAndRepeat() {
        val host: Fake = Fake()
        val keys: ColorKeyActions = ColorKeyActions(host)
        check(keys.key("mov.film", true, 0), "Configured key was not consumed")
        keys.key("mov.film", true, 1)
        keys.key("mov.film", false, 0)
        check(
            host.launched.size == 1 && host.dismissals == 0,
            "Awake key repeated or tried to wake",
        )
        check(!keys.key("none", true, 0) && !keys.key(null, true, 0), "Disabled key was consumed")
    }

    private fun launchDuringDream() {
        val host: Fake = Fake()
        val keys: ColorKeyActions = ColorKeyActions(host)
        keys.dreamStarted() // Also initial state when the service reconnects into an existing
        // dream.
        keys.key("mov.film", true, 0)
        keys.key("mov.film", true, 1)
        keys.key("mov.film", false, 0)
        check(
            host.launched.size == 1,
            "Film waited for the screensaver instead of launching immediately",
        )
        check(
            host.dismissals == 1 && host.timeout != null,
            "Dream dismissal was missing or repeated",
        )
        host.foreground = "home" // Asynchronous HOME wins after the first launch.
        keys.homeShown()
        check(
            "mov.film" == host.foreground && host.restored.size == 1,
            "HOME obscured the target during dream exit",
        )
        keys.dreamStopped()
        keys.dreamStopped()
        check(
            host.launched.size == 1 && host.restored.size == 1,
            "Visible target was unnecessarily restarted",
        )
        host.foreground = "home" // Late HOME after STOPPED, without a window event.
        host.fireTimeout()
        check(
            "mov.film" == host.foreground && host.restored.size == 2,
            "Late HOME won after dream closure",
        )
        check(
            host.timeout == null && host.failures == 0,
            "Successful settlement retained work or reported failure",
        )
    }

    private fun concreteTargetAndReplacement() {
        val host: Fake = Fake()
        val keys: ColorKeyActions = ColorKeyActions(host)
        keys.dreamStarted()
        keys.key("hdmi-toggle", true, 0)
        check("hdmi" == host.foreground, "HDMI did not start immediately")
        host.foreground = "home"
        keys.homeShown()
        keys.dreamStopped()
        check(
            host.prepared == 1 && "hdmi" == host.foreground,
            "Restoration evaluated HDMI toggle twice",
        )
        keys.key("inputs", true, 0) // New shortcut while the previous HOME correction is settling.
        host.foreground = "home"
        host.fireTimeout()
        check("inputs" == host.foreground, "Old correction overwrote a newer shortcut")

        keys.dreamStarted()
        keys.key("mov.film", true, 0)
        keys.key("next-wallpaper", true, 0)
        check(
            host.dismissals == 2 && host.wallpaperChanges == 1,
            "Repeated dismissal or missing wallpaper action",
        )
        host.foreground = "dream"
        keys.dreamStopped()
        host.fireTimeout()
        check(
            "home" == host.foreground && host.wallpaperChanges == 1,
            "Restoration repeated the wallpaper change",
        )
    }

    private fun timeoutAndCancellation() {
        val host: Fake = Fake()
        val keys: ColorKeyActions = ColorKeyActions(host)
        keys.dreamStarted()
        keys.key("inputs", true, 0)
        host.foreground = "home"
        host.fireTimeout()
        check(
            host.failures == 0 && "inputs" == host.foreground,
            "Lost STOPPED left HOME over the target",
        )
        keys.key("inputs", true, 0)
        check(host.dismissals == 1, "Stale dream state trapped later shortcuts")
        keys.dreamStarted() // A real screensaver is re-observed before the next shortcut.
        keys.key("inputs", true, 0)
        check(host.dismissals == 2, "Re-observed dream was not dismissed")
        keys.reset()
        val restored: Int = host.restored.size
        host.foreground = "other"
        keys.dreamStopped()
        keys.homeShown()
        check(
            host.timeout == null && host.restored.size == restored,
            "Cancelled correction survived lifecycle reset",
        )

        keys.dreamStarted()
        keys.key("mov.film", true, 0)
        keys.cancelCorrection() // The user explicitly pressed HOME/BACK/DPAD instead.
        host.foreground = "home"
        keys.dreamStopped()
        keys.homeShown()
        check(
            host.timeout == null && "home" == host.foreground,
            "Old shortcut stole focus after explicit navigation",
        )

        host.allowDismissal = false
        keys.dreamStarted()
        keys.key("mov.film", true, 0)
        check("mov.film" == host.foreground, "Rejected dismissal prevented immediate launch")
        check(host.failures == 0 && host.timeout != null, "Rejected dismissal reported failure before checking the window")
        host.fireTimeout()
        check(host.failures == 0, "A visible target was reported as an active screensaver")
    }

    private fun check(passed: Boolean, message: String) {
        if (!passed) throw AssertionError(message)
    }

    private class Fake : ColorKeyActions.Host {
        val launched: ArrayList<String> = ArrayList<String>()
        val restored: ArrayList<String> = ArrayList<String>()
        var allowDismissal: Boolean = true
        var dismissals: Int = 0
        var failures: Int = 0
        var prepared: Int = 0
        var wallpaperChanges: Int = 0
        var foreground: String? = "other"
        var timeout: Runnable? = null

        override fun prepare(action: String): ColorKeyActions.Target? {
            prepared++
            val target: String =
                if ("hdmi-toggle" == action) (if ("hdmi" == foreground) "home" else "hdmi")
                else if ("next-wallpaper" == action) "home" else action
            return object : ColorKeyActions.Target {
                override fun launch() {
                    launched.add(target)
                    foreground = target
                    if ("next-wallpaper" == action) wallpaperChanges++
                }

                override fun restoreIfNeeded() {
                    if (target != foreground && (foreground == "home" || foreground == "dream")) {
                        restored.add(target)
                        foreground = target
                    }
                }
            }
        }

        override fun dismissDream(): Boolean {
            dismissals++
            return allowDismissal
        }

        override fun isDreamVisible(): Boolean = foreground == "dream"

        override fun scheduleTimeout(action: Runnable, delayMs: Long) {
            check(
                timeout == null && delayMs > 0 && delayMs <= 3000,
                "Timeout is missing, unbounded or duplicated",
            )
            timeout = action
        }

        override fun cancelTimeout(action: Runnable) {
            if (timeout === action) timeout = null
        }

        override fun trace(event: String) {}

        override fun dismissalFailed() {
            failures++
        }

        fun fireTimeout() {
            check(timeout != null, "Missing timeout")
            val action: Runnable = requireNotNull(timeout)
            timeout = null
            action.run()
        }
    }
}
