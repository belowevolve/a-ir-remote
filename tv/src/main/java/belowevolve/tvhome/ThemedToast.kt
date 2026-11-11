package belowevolve.tvhome

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import belowevolve.shared.design.RemoteShapes
import belowevolve.shared.design.RemoteSpacing

/** A non-interactive transient message owned by the already-enabled key service. */
internal class ThemedToast(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val dismiss = Runnable { close() }

    fun ownsWindow(id: Int): Boolean = id >= 0 && view?.createAccessibilityNodeInfo()?.let { node ->
        try { node.windowId == id } finally { node.recycle() }
    } == true

    fun show(message: String): Boolean = runCatching {
        close()
        // :keys must not cache another process's SharedPreferences palette.
        val palette = service.contentResolver.call(Uri.parse("content://belowevolve.tvhome.theme"), "palette", null, null)
        val surface = palette?.getInt("surface", Ui.PANEL) ?: Ui.PANEL
        val foreground = palette?.getInt("text", Ui.TEXT) ?: Ui.TEXT
        val accent = palette?.getInt("accent_hot", Ui.PRIMARY) ?: Ui.PRIMARY
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(service, RemoteSpacing.Large), Ui.dp(service, RemoteSpacing.Medium),
                Ui.dp(service, RemoteSpacing.Large), Ui.dp(service, RemoteSpacing.Medium))
            background = GradientDrawable().apply {
                setColor(surface); cornerRadius = Ui.dp(service, RemoteShapes.Medium).toFloat()
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        row.addView(ImageView(service).apply {
            setImageResource(R.drawable.ic_air_home_monochrome)
            imageTintList = ColorStateList.valueOf(accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(service, 36f), Ui.dp(service, 36f)))
        row.addView(TextView(service).apply {
            text = message; textSize = 16f; setTextColor(foreground)
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            maxWidth = minOf(Ui.dp(service, 480f), service.resources.displayMetrics.widthPixels - Ui.dp(service, 120f))
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(service, RemoteSpacing.Medium) })
        val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = Ui.dp(service, 64f)
            title = "Air Home message"
        }
        windows.addView(row, params)
        view = row
        main.postDelayed(dismiss, 2_800)
        true
    }.getOrElse {
        android.util.Log.w("AirHome", "Themed message unavailable", it)
        false
    }

    fun close() {
        main.removeCallbacks(dismiss)
        view?.let { runCatching { windows.removeViewImmediate(it) } }
        view = null
    }

    companion object {
        const val ACTION = "belowevolve.tvhome.SHOW_MESSAGE"
        fun post(context: Context, message: String) {
            val app = context.applicationContext
            app.sendOrderedBroadcast(Intent(ACTION).setPackage(app.packageName).putExtra("message", message), null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (resultCode != Activity.RESULT_OK) Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
                    }
                }, null, Activity.RESULT_CANCELED, null, null)
        }
    }
}
