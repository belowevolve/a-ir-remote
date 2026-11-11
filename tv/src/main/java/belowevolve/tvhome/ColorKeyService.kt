package belowevolve.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.database.ContentObserver
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
    private var remoteLaunches: BroadcastReceiver? = null
    private val messages by lazy { ThemedToast(this) }
    private var remoteWakePending = false
    private val clearRemoteWake = Runnable { remoteWakePending = false }
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

                override fun isDreamVisible(): Boolean? = activeWindowPackage()?.let(dreamPackages::contains)

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
            }
        )
    private val screens: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val action: String? = i.action
                if (Intent.ACTION_DREAMING_STARTED == action) {
                    resetRemoteWake()
                    WakeTrace.record(c, "dream_started")
                    shortcuts.dreamStarted()
                } else if (Intent.ACTION_DREAMING_STOPPED == action) {
                    resetRemoteWake()
                    WakeTrace.record(c, "dream_stopped")
                    shortcuts.dreamStopped()
                } else if (Intent.ACTION_SCREEN_OFF == action) {
                    resetRemoteWake()
                    WakeTrace.record(c, "screen_off")
                    shortcuts.reset()
                } else if (Intent.ACTION_SCREEN_ON == action) {
                    WakeTrace.record(c, "screen_on")
                }
            }
        }

    private var lastVisible: String? = ""
    private val bindings = HashMap<Int, String>()
    private val bindingChanges =
        object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                reloadBindings()
            }
        }

    private fun reloadBindings() {
        runCatching {
            val snapshot =
                requireNotNull(contentResolver.call(Actions.bindingsUri, "bindings", null, null))
            for (key in 183..186) {
                snapshot.getString(key.toString())?.let { bindings[key] = it }
            }
        }
            .onFailure {
                if (bindings.isEmpty())
                    for (key in 183..186) bindings[key] = Actions.binding(this, key)
                android.util.Log.w("AirKeys", "Bindings unavailable")
            }
    }

    protected override fun onServiceConnected() {
        super.onServiceConnected()
        if (!tracing) {
            contentResolver.registerContentObserver(Actions.bindingsUri, false, bindingChanges)
            val filter: IntentFilter = IntentFilter(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_DREAMING_STARTED)
            filter.addAction(Intent.ACTION_DREAMING_STOPPED)
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(screens, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(screens, filter)
            tracing = true
        }
        reloadBindings()
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
        registerRemoteLaunches()
        registerDebugKeys()
    }

    override fun onDestroy() {
        messages.close()
        contentResolver.unregisterContentObserver(bindingChanges)
        shortcuts.reset()
        resetRemoteWake()
        if (debugKeys != null) unregisterReceiver(debugKeys)
        if (remoteLaunches != null) unregisterReceiver(remoteLaunches)
        debugKeys = null
        remoteLaunches = null
        if (tracing) unregisterReceiver(screens)
        tracing = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (messages.ownsWindow(e.windowId)) return
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
            if (messages.ownsWindow(root.windowId)) return null
            return (root.getPackageName()).toString()
        } finally {
            root.recycle()
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerRemoteLaunches() {
        if (remoteLaunches != null) return
        remoteLaunches = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == MediaSeek.ACTION) {
                    val seconds = intent.getIntExtra("seconds", 0).takeIf { it != 0 && it in -6000..6000 } ?: return
                    resultCode = android.app.Activity.RESULT_OK
                    MediaSeek.perform(this@ColorKeyService, seconds, activeWindowPackage())
                    return
                }
                if (intent.action == ThemedToast.ACTION) {
                    val message = intent.getStringExtra("message")?.take(240) ?: return
                    if (messages.show(message)) resultCode = android.app.Activity.RESULT_OK
                    return
                }
                val route = RemoteLaunchAction.fromRoute("airhome", "remote", intent.getStringExtra("path")) ?: return
                if (route == RemoteLaunchAction.WAKE) {
                    // BACK is consumed by the system dream and reveals the existing player.
                    // Never inject it into an ordinary application window.
                    if (!remoteWakePending && dreamPackages.contains(activeWindowPackage())) {
                        WakeTrace.record(this@ColorKeyService, "remote_dream_back")
                        remoteWakePending = performGlobalAction(GLOBAL_ACTION_BACK)
                        if (remoteWakePending) main.postDelayed(clearRemoteWake, ColorKeyActions.DISMISS_TIMEOUT_MS)
                    }
                    resultCode = android.app.Activity.RESULT_OK
                    return
                }
                val target = route.shortcut ?: return
                inspectDreamWindow()
                WakeTrace.record(this@ColorKeyService, "remote_" + route.name.lowercase())
                shortcuts.key(target, true, 0)
                resultCode = android.app.Activity.RESULT_OK
            }
        }
        val filter = IntentFilter(RemoteLaunchAction.BROADCAST).apply { addAction(ThemedToast.ACTION); addAction(MediaSeek.ACTION) }
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(remoteLaunches, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(remoteLaunches, filter, "belowevolve.tvhome.permission.INTERNAL_CONTROL", main)
    }

    private fun resetRemoteWake() {
        remoteWakePending = false
        main.removeCallbacks(clearRemoteWake)
    }

    @android.annotation.SuppressLint(
        "UnspecifiedRegisterReceiverFlag"
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
                        if (down && up) "delivered_to_bound_service" else "unbound_shortcut"
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
        else if (k >= 183 && k <= 186) action = bindings[k] ?: "none"
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

    override fun dump(
        fd: java.io.FileDescriptor?,
        writer: java.io.PrintWriter,
        args: Array<out String>?,
    ) {
        super.dump(fd, writer, args)
        WakeTrace.dump(this, "", writer)
        writer.println("AIRHOME_BINDINGS " + bindings)
    }
}
