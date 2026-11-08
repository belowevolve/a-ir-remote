package belowevolve.tvhome

import android.app.*
import android.content.*
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.*
import android.view.*
import android.widget.*
import belowevolve.shared.design.RemoteIcons
import belowevolve.shared.design.RemotePalette
import belowevolve.shared.design.RemoteShapes
import belowevolve.shared.design.RemoteSpacing

object Ui {
    private const val CardCornerDp = 12f
    // Cached wallpaper roles; Air Remote's base palette is the first-launch fallback.
    var BG: Int = RemotePalette.Background
    var TEXT: Int = RemotePalette.Text
    var MUTED: Int = RemotePalette.Muted
    var PANEL: Int = RemotePalette.Surface
    var PRIMARY: Int = RemotePalette.Primary
    var ON_PRIMARY: Int = RemotePalette.OnPrimary
    var PRESSED: Int = -0x5d287f

    fun useTheme(theme: WallpaperTheme?) {
        if (theme == null) return
        val colors: IntArray = theme.colors
        BG = colors[0]
        TEXT = colors[1]
        MUTED = colors[2]
        PANEL = colors[3]
        PRIMARY = colors[4]
        ON_PRIMARY = colors[5]
        PRESSED = colors[6]
    }

    fun dp(c: Context, n: Float): Int {
        return Math.round(n * c.getResources().getDisplayMetrics().density)
    }

    fun fullscreen(a: Activity) {
        useTheme(WallpaperTheme.load(a))
        a.getWindow().setWindowAnimations(0)
        a.getWindow().getDecorView().setSystemUiVisibility(5894)
        a.getWindow().setStatusBarColor(Color.TRANSPARENT)
        a.getWindow().setNavigationBarColor(Color.TRANSPARENT)
        a.getWindow().getDecorView().setSoundEffectsEnabled(false)
    }

    fun posterAccent(bitmap: Bitmap?): Int {
        if (bitmap == null || bitmap.isRecycled()) return -0x655e56
        val hsv: FloatArray = FloatArray(3)
        // Keep hue families separate: averaging unrelated colors invents pink/purple.
        // Ignore the poster margins where film.mov paints its own episode badges.
        val weights: FloatArray = FloatArray(24 * 4 * 4)
        val bestWeight: FloatArray = FloatArray(weights.size)
        val colors: IntArray = IntArray(weights.size)
        val stepX: Int = Math.max(1, bitmap.getWidth() / 32)
        val stepY: Int = Math.max(1, bitmap.getHeight() / 32)
        var y: Int = bitmap.getHeight() / 5
        while (y < bitmap.getHeight() * 4 / 5) {
            var x: Int = bitmap.getWidth() / 10
            while (x < bitmap.getWidth() * 9 / 10) {
                val color: Int = bitmap.getPixel(x, y)
                Color.colorToHSV(color, hsv)
                if (Color.alpha(color) < 128 || hsv[1] < .15f || hsv[2] < .15f) {
                    x += stepX
                    continue
                }
                val weight: Float = Math.sqrt((hsv[1] * hsv[2]).toDouble()).toFloat()
                val bin: Int =
                    (Math.min(23, (hsv[0] / 15).toInt()) * 16 +
                        Math.min(3, (hsv[1] * 4).toInt()) * 4 +
                        Math.min(3, (hsv[2] * 4).toInt()))
                weights[bin] += weight
                if (weight > bestWeight[bin]) {
                    bestWeight[bin] = weight
                    colors[bin] = color
                }
                x += stepX
            }
            y += stepY
        }
        var dominant: Int = 0
        for (i: Int in 1 until weights.size) if (weights[i] > weights[dominant]) dominant = i
        // Use a pixel actually present in the artwork; monochrome artwork stays neutral.
        return if (weights[dominant] > 0) colors[dominant] else -0x655e56
    }

    fun toast(c: Context, s: String) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show() }
}
