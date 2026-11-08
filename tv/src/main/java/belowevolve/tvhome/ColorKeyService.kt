package belowevolve.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.HashSet

class ColorKeyService : AccessibilityService() {
    private var tracing: Boolean = false
    private var debugKeys: BroadcastReceiver? = null
    private val main: Handler = Handler(Looper.getMainLooper())
    private val dreamPackages: HashSet<String> = HashSet<String>()
    private val shortcuts: ColorKeyActions =
        ColorKeyActions(
            object : ColorKeyActions.Host {
                override fun prepare(action: String): ColorKeyActions.Target? {
                    val prepared: Actions.PreparedAction? =
                        Actions.prepare(this@ColorKeyService, action)
                    if (prepared == null) return null
                    return object : ColorKeyActions.Target {
                        override fun launch() {
                            WakeTrace.record(this@ColorKeyService, "launch_" + action)
                            prepared.launch(false)
                        }

                        override fun restoreIfNeeded() {
                            val pkg: String? = activeWindowPackage()
                            if (
                                prepared.canRestore(pkg, lastVisible, dreamPackages.contains(pkg))
                            ) {
                                WakeTrace.record(this@ColorKeyService, "restore_" + action)
                                prepared.launch(true)
                            }
                        }
                    }
                }

                override fun dismissDream(): Boolean {
                    // Android's HOME policy explicitly stops a dream before launching HOME. No
                    // POWER toggle.
                    WakeTrace.record(this@ColorKeyService, "dream_dismiss_home")
                    return performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }

                override fun scheduleTimeout(timeout: Runnable, delayMs: Long) {
                    main.postDelayed(timeout, delayMs)
                }

                override fun cancelTimeout(timeout: Runnable) {
                    main.removeCallbacks(timeout)
                }

                override fun trace(event: String) {
                    WakeTrace.record(this@ColorKeyService, event)
                }

                override fun dismissalFailed() {
                    Ui.toast(
                        this@ColorKeyService,
                        "Не удалось закрыть заставку. Нажмите «Домой» и повторите.",
                    )
                }
            },
        )
    private val screens: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val action: String? = i.action
                if (Intent.ACTION_DREAMING_STARTED == action) {
                    WakeTrace.record(c, "dream_started")
                    shortcuts.dreamStarted()
                } else if (Intent.ACTION_DREAMING_STOPPED == action) {
                    WakeTrace.record(c, "dream_stopped")
                    shortcuts.dreamStopped()
                } else if (Intent.ACTION_SCREEN_OFF == action) {
                    WakeTrace.record(c, "screen_off")
                    shortcuts.reset()
                } else if (Intent.ACTION_SCREEN_ON == action) {
                    WakeTrace.record(c, "screen_on")
                }
            }
        }

    private var lastVisible: String? = ""

    protected override fun onServiceConnected() {
        super.onServiceConnected()
        if (!tracing) {
            val filter: IntentFilter = IntentFilter(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_DREAMING_STARTED)
            filter.addAction(Intent.ACTION_DREAMING_STOPPED)
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(screens, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(screens, filter)
            tracing = true
        }
        dreamPackages.clear()
        dreamPackages.add("com.google.android.backdrop")
        try {
            val configured: String? =
                Settings.Secure.getString(getContentResolver(), "screensaver_components")
            if (configured != null)
                for (name: String in
                    configured
                        .split((",").toRegex())
                        .dropLastWhile({ it.isEmpty() })
                        .toTypedArray()) {
                    val component: ComponentName? = ComponentName.unflattenFromString(name)
                    if (component != null) dreamPackages.add(component.getPackageName())
                }
        } catch (unavailable: SecurityException) {
            // Some TVs do not expose this setting. Dream broadcasts still cover other screensavers.
        }

        WakeTrace.record(this, "keys_ready")
        // Dream broadcasts are not sticky: an already-running screensaver needs an initial
        // snapshot.
        inspectDreamWindow()
        registerDebugKeys()
    }

    override fun onDestroy() {
        shortcuts.reset()
        if (debugKeys != null) unregisterReceiver(debugKeys)
        debugKeys = null
        if (tracing) unregisterReceiver(screens)
        tracing = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg: String = (e.getPackageName()).toString()
        val cls: String = (e.getClassName()).toString()
        var role: String? = null
        if (dreamPackages.contains(pkg)) {
            role = "dream"
            inspectDreamWindow()
        } else if (pkg == "mov.film") role = "film"
        else if (pkg == getPackageName()) {
            if (cls.endsWith("HomeActivity")) role = "home"
            else if (cls.endsWith("HdmiActivity")) role = "hdmi"
            else if (cls.endsWith("SettingsActivity")) role = "settings"
        }
        if (role != null && role != lastVisible) WakeTrace.record(this, "visible_" + role)
        lastVisible = if (role == null) "" else role
        if ("home" == role) shortcuts.homeShown()
    }

    override fun onInterrupt() {
        shortcuts.reset()
    }

    private fun inspectDreamWindow() {
        if (dreamPackages.contains(activeWindowPackage())) {
            shortcuts.dreamStarted()
            WakeTrace.record(this, "dream_observed")
        }
    }

    private fun activeWindowPackage(): String? {
        // Only the root's package metadata is read. Never traverse nodes or inspect screen text.
        val root: AccessibilityNodeInfo? = getRootInActiveWindow()
        if (root == null) return null
        try {
            return (root.getPackageName()).toString()
        } finally {
            root.recycle()
        }
    }

    @android.annotation.SuppressLint(
        "UnspecifiedRegisterReceiverFlag",
    ) // Explicit flags on API 33+; DUMP guards both paths.
    private fun registerDebugKeys() {
        // Shell-injected key events can bypass the accessibility filter. Only debuggable builds
        // expose this diagnostic, and only callers holding the platform DUMP permission can use it.
        if (
            debugKeys != null ||
                (getApplicationInfo().flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0
        )
            return
        debugKeys =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val key: Int = intent.getIntExtra("keycode", -1)
                    if (!((key >= 183 && key <= 186) || key == KeyEvent.KEYCODE_TV_INPUT)) return
                    val now: Long = SystemClock.uptimeMillis()
                    val down: Boolean = onKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
                    val up: Boolean = onKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
                    setResultCode(if (down && up) 1 else 0)
                    setResultData(
                        if (down && up) "delivered_to_bound_service" else "unbound_shortcut",
                    )
                }
            }
        val filter: IntentFilter = IntentFilter("belowhome.DEBUG_COLOR_KEY")
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(
                debugKeys,
                filter,
                "android.permission.DUMP",
                main,
                Context.RECEIVER_EXPORTED,
            )
        else registerReceiver(debugKeys, filter, "android.permission.DUMP", main)
    }

    protected override fun onKeyEvent(e: KeyEvent): Boolean {
        val k: Int = e.getKeyCode()
        val action: String
        if (k == KeyEvent.KEYCODE_TV_INPUT) action = "inputs"
        else if (k >= 183 && k <= 186) action = Actions.binding(this, k)
        else {
            if (
                e.getAction() == KeyEvent.ACTION_DOWN &&
                    e.getRepeatCount() == 0 &&
                    isNavigationKey(k)
            )
                shortcuts.cancelCorrection()
            return false
        }
        if ("none" == action) return false
        val down: Boolean = e.getAction() == KeyEvent.ACTION_DOWN
        if (down && e.getRepeatCount() == 0) {
            inspectDreamWindow()
            WakeTrace.record(this, "key_" + k + "_action_" + action)
        }
        return shortcuts.key(action, down, e.getRepeatCount())
    }

    private fun isNavigationKey(key: Int): Boolean {
        return (key == KeyEvent.KEYCODE_HOME ||
            key == KeyEvent.KEYCODE_BACK ||
            key == KeyEvent.KEYCODE_DPAD_UP ||
            key == KeyEvent.KEYCODE_DPAD_DOWN ||
            key == KeyEvent.KEYCODE_DPAD_LEFT ||
            key == KeyEvent.KEYCODE_DPAD_RIGHT ||
            key == KeyEvent.KEYCODE_DPAD_CENTER ||
            key == KeyEvent.KEYCODE_ENTER ||
            key == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            key == KeyEvent.KEYCODE_ESCAPE)
    }
}
