package belowevolve.tvhome

import android.app.WallpaperColors
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import belowevolve.tvhome.material.hct.Hct

/* Wallpaper seed + dark Material tonal roles. Extraction runs on the wallpaper worker. */
class WallpaperTheme
private constructor(val seed: Int, val colors: IntArray, val imageKey: String = "") {

    fun filmPalette(): android.os.Bundle {
        val source: Hct = Hct.fromInt(colors[4])
        val fill: Int =
            tone(source.getHue(), (if (source.getChroma() < 5) 0 else 36).toDouble(), 40.0)
        val result: android.os.Bundle = android.os.Bundle()
        result.putInt("v", 1)
        result.putInt("accent", fill)
        result.putInt("accent_hot", colors[4])
        result.putInt("accent_soft", (fill and 0xffffff) or 0x66000000)
        result.putInt("back", colors[0])
        result.putInt("surface", colors[3])
        result.putInt("text", colors[1])
        result.putInt("muted", colors[2])
        return result
    }

    fun save(c: Context, key: String) {
        val p: SharedPreferences = c.getSharedPreferences("wallpaper_theme", 0)
        if (key == p.getString("image", "")) return
        val e: SharedPreferences.Editor = p.edit().putString("image", key).putInt("seed", seed)
        for (i: Int in colors.indices) e.putInt("color_" + i, colors[i])
        e.apply()
        android.util.Log.i(
            "AirHome",
            ("THEME image=" +
                key +
                " seed=" +
                Integer.toHexString(seed) +
                " primary=" +
                Integer.toHexString(colors[4])),
        )
    }

    companion object {

        fun load(c: Context): WallpaperTheme? {
            val p: SharedPreferences = c.getSharedPreferences("wallpaper_theme", 0)
            val snapshot: Map<String, *> = p.getAll()
            if (!snapshot.containsKey("color_6")) return null
            val colors: IntArray = IntArray(7)
            for (i: Int in colors.indices) {
                val value: Any? = snapshot.get("color_" + i)
                if (!(value is Number)) return null
                colors[i] = (value as Number).toInt()
            }
            val seed: Any? = snapshot.get("seed")
            if (!(seed is Number)) return null
            return WallpaperTheme(
                (seed as Number).toInt(),
                colors,
                if (snapshot.get("image") is String) snapshot.get("image") as String else "",
            )
        }

        fun fromImage(c: Context, image: Bitmap, key: String): WallpaperTheme? {
            val cached: WallpaperTheme? = load(c)
            if (cached != null && key == cached.imageKey) return cached
            val sample: Bitmap = Bitmap.createScaledBitmap(image, 128, 72, true)
            val seed: Int
            try {
                seed = WallpaperColors.fromBitmap(sample).getPrimaryColor().toArgb()
            } finally {
                if (sample != image) sample.recycle()
            }
            val source: Hct = Hct.fromInt(seed)
            val hue: Double = source.getHue()
            // Tonal Spot chroma, with neutral artwork kept neutral instead of adding an arbitrary
            // hue.
            val chroma: Double = (if (source.getChroma() < 5) 0 else 36).toDouble()
            return WallpaperTheme(
                seed,
                intArrayOf(
                    tone(hue, 6.0, 6.0),
                    tone(hue, 6.0, 90.0),
                    tone(hue, 8.0, 80.0),
                    tone(hue, 6.0, 12.0),
                    tone(hue, chroma, 80.0),
                    tone(hue, chroma, 20.0),
                    tone(hue, chroma, 70.0),
                ),
            )
        }

        private fun tone(hue: Double, chroma: Double, tone: Double): Int {
            return Hct.from(hue, chroma, tone).toInt()
        }
    }
}
