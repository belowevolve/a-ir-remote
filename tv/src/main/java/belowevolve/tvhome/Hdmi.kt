package belowevolve.tvhome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.os.Build
import android.os.Bundle

/** One viewer per device: the Haier source controller, or the generic TvView fallback. */
internal object Hdmi {
    const val DEFAULT_INPUT = "com.mediatek.tvinput/.hdmi.HDMIInputService/HW8"
    const val NATIVE_PACKAGE = "com.mediatek.wwtv.tvcenter"
    private val nativeComponent = ComponentName(NATIVE_PACKAGE, "$NATIVE_PACKAGE.nav.TurnkeyUiMainActivity")

    private fun state(context: Context): Bundle =
        requireNotNull(context.contentResolver.call(Actions.bindingsUri, "hdmi-state", null, null))

    fun intent(context: Context, input: String?): Intent {
        // The key service has its own process and SharedPreferences cache. Read the last input
        // from the main process, where the input picker stores it.
        val selected = input ?: state(context).getString("input") ?: DEFAULT_INPUT
        if (input != null)
            context.getSharedPreferences("home", 0).edit().putString("last_hdmi", input).apply()
        val native = Build.HARDWARE == "m7632" && Build.DEVICE == "daan" && runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getActivityInfo(nativeComponent, 0)
            info.exported && info.enabled && info.applicationInfo?.enabled == true
        }.getOrDefault(false)
        return if (native)
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(selected))
                .setComponent(nativeComponent)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        else
            Intent(context, HdmiActivity::class.java)
                .putExtra("input", selected)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun isForeground(context: Context, windowPackage: String?, lastRole: String?): Boolean {
        if (windowPackage == NATIVE_PACKAGE) return true
        if (windowPackage == null || isSystemOverlay(windowPackage)) return lastRole == "hdmi"
        if (windowPackage != null && windowPackage != context.packageName) return false
        return runCatching { state(context).getBoolean("foreground") }
            .getOrElse { lastRole == "hdmi" }
    }

    fun isSystemOverlay(packageName: String): Boolean =
        packageName == "com.android.systemui" || packageName == "com.android.tv.settings"
}
