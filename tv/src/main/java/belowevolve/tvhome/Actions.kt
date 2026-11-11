package belowevolve.tvhome

import android.app.*
import android.content.*
import android.net.*
import android.view.*
import android.widget.*

object Actions {
    val bindingsUri: android.net.Uri =
        android.net.Uri.parse("content://belowevolve.tvhome.theme/bindings")

    fun setBinding(c: Context, key: Int, action: String) {
        c.getSharedPreferences("home", 0).edit().putString("key_$key", action).apply()
        c.contentResolver.notifyChange(bindingsUri, null)
    }

    val VIDEO_APP: String = "io.gh.reisxd.tizentube.cobalt"

    var lastVpnToggle: Long = 0

    fun binding(c: Context, key: Int): String {
        val p: android.content.SharedPreferences = c.getSharedPreferences("home", 0)
        if (!p.getBoolean("blue_wallpaper_v1", false))
            p.edit()
                .putString("key_186", "next-wallpaper")
                .putBoolean("blue_wallpaper_v1", true)
                .apply()
        if (!p.getBoolean("controls_v2", false)) {
            val e: android.content.SharedPreferences.Editor =
                p.edit()
                    .putString("key_183", "hdmi-toggle")
                    .putString("key_184", "mov.film")
                    .putString("key_185", "inputs")
                    .putBoolean("controls_v2", true)
            val blue: String = p.getString("key_186", "none") ?: "none"
            if (blue == "vpn" || blue == "vpn-toggle") e.putString("key_186", "none")
            e.apply()
        }
        val action: String =
            p.getString(
                "key_" + key,
                if (key == 183) "hdmi-toggle"
                else if (key == 184) "mov.film" else if (key == 185) "inputs" else "none",
            ) ?: "none"
        if ("hdmi" == action) return "hdmi-toggle"
        return action
    }

    fun color(c: Context, key: Int): Boolean {
        if (key < 183 || key > 186) return false
        val action: String = binding(c, key)
        if ("none" == action) return false
        run(c, action)
        return true
    }

    fun toggleVpn(c: Context) {
        val now: Long = android.os.SystemClock.elapsedRealtime()
        if (now - lastVpnToggle < 1500) return
        if (
            (c.checkSelfPermission("org.amnezia.awg.permission.CONTROL_TUNNELS") !=
                android.content.pm.PackageManager.PERMISSION_GRANTED)
        ) {
            Ui.toast(c, "Нет разрешения управления Amnezia")
            return
        }
        lastVpnToggle = now
        val on: Boolean = SettingsActivity.vpnOn(c)
        c.sendBroadcast(
            Intent(
                    if (on) "org.amnezia.awg.action.SET_TUNNEL_DOWN"
                    else "org.amnezia.awg.action.SET_TUNNEL_UP"
                )
                .setComponent(
                    ComponentName(
                        "org.amnezia.awg",
                        "org.amnezia.awg.model.TunnelManager\$IntentReceiver",
                    )
                )
                .putExtra("tunnel", "tv-home")
        )
        Ui.toast(c, if (on) "Отключение VPN" else "Подключение VPN")
    }

    fun homeIntent(c: Context): Intent {
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setClass(c, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun run(c: Context, action: String) {
        val prepared: PreparedAction? = prepare(c, action)
        if (prepared != null) prepared.launch(false)
    }

    /* Resolve stateful bindings once, before HOME/dream callbacks can change foreground state. */
    fun prepare(c: Context, action: String): PreparedAction? {
        try {
            var i: Intent?
            if ("hdmi-toggle" == action)
                i = if (HdmiActivity.isForeground) homeIntent(c) else HdmiActivity.intent(c, null)
            else if ("hdmi-open" == action) i = HdmiActivity.intent(c, null)
            else if ("next-wallpaper" == action) i = homeIntent(c).putExtra("next_wallpaper", true)
            else if ("inputs" == action)
                i = Intent(c, SettingsActivity::class.java).putExtra("section", "inputs")
            else if ("settings" == action || "vpn" == action)
                i = Intent(c, SettingsActivity::class.java).putExtra("section", action)
            else {
                i = c.getPackageManager().getLeanbackLaunchIntentForPackage(action)
                if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(action)
                if (i == null) throw IllegalArgumentException("Приложение недоступно")
            }
            return PreparedAction(c, action, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Ui.toast(c, "Не удалось открыть: " + action)
            android.util.Log.w("AirHome", "Action failed", e)
            return null
        }
    }

    class PreparedAction(
        private val context: Context,
        private val action: String,
        val initial: Intent,
    ) {
        val restore: Intent

        init {
            restore = Intent(initial)
            // Restoring visibility must not repeat the one-shot wallpaper change.
            restore.removeExtra("next_wallpaper")
            restore.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        fun launch(restoring: Boolean) {
            try {
                context.startActivity(Intent(if (restoring) restore else initial))
            } catch (error: Exception) {
                Ui.toast(context, "Не удалось открыть: " + action)
                android.util.Log.w("AirHome", "Action failed", error)
            }
        }

        fun matchesWindow(packageName: String?, ownRole: String?): Boolean {
            val component: ComponentName? = initial.getComponent()
            if (component == null || component.getPackageName() != packageName) return false
            if (context.getPackageName() != packageName) return true
            val name: String = component.getClassName()
            return ((name.endsWith("HomeActivity") && "home" == ownRole) ||
                (name.endsWith("HdmiActivity") && "hdmi" == ownRole) ||
                (name.endsWith("SettingsActivity") && "settings" == ownRole))
        }

        fun canRestore(packageName: String?, ownRole: String?, dreamVisible: Boolean): Boolean {
            if (packageName == null || matchesWindow(packageName, ownRole)) return false
            // Never override an unrelated app or guess when window metadata is unavailable.
            return dreamVisible || (context.getPackageName() == packageName && "home" == ownRole)
        }
    }

    fun openUri(c: Context, uri: String) {
        try {
            val i: Intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        } catch (e: Exception) {
            Ui.toast(c, "Не удалось открыть фильм")
        }
    }
}
