package belowevolve.tvhome

import android.content.Context
import android.content.SharedPreferences

/* Shared geometry for Home, previews and wallpaper treatment. Distances are dp. */
class HomeLayout(c: Context) {
    val mode: Int
    val bottom: Int
    val rowTop: Int
    val posterTop: Int
    val captionStyle: Int
    val motion: Int
    val gap: Int
    val showTitle: Boolean

    init {
        val p: SharedPreferences = c.getSharedPreferences("appearance", 0)
        mode = clamp(p.getInt("layout", 0), 0, 1)
        bottom = clamp(p.getInt("bottom_inset", 32), 24, 160)
        posterTop = if (mode == 0) 194 else 540 - bottom - 156
        rowTop = posterTop - 16
        val savedCaption: Int =
            p.getInt("caption_style", if (p.getBoolean("show_title", true)) 1 else 3)
        // Preserve IDs: retired badges become inline text; the old panel becomes two lines.
        captionStyle =
            if (savedCaption == 5) 4
            else if (savedCaption == 2 || savedCaption < 0 || savedCaption > 4) 1 else savedCaption
        showTitle = captionStyle != 3
        // Retired Dock falls back to the original animation. Preserve the old static choice.
        motion = clamp(p.getInt("card_motion", if (p.getInt("card_visual", 0) == 2) 4 else 0), 0, 4)
        gap = 12
    }

    fun captionIndex(): Int {
        for (i: Int in CAPTION_IDS.indices) if (CAPTION_IDS[i] == captionStyle) return i
        return 1
    }

    companion object {
        val NAMES: Array<String> = arrayOf<String>("Лента по центру", "Нижняя полка")
        val CAPTION_IDS: IntArray = intArrayOf(0, 1, 4, 3)
        val CAPTIONS: Array<String> =
            arrayOf<String>("Название", "Название и серия", "В одну строку", "Без подписи")
        val MOTIONS: Array<String> =
            arrayOf<String>("Текущая", "Мягкая", "Быстрая", "Пружина", "Без анимации")

        private fun clamp(v: Int, min: Int, max: Int): Int {
            return Math.max(min, Math.min(max, v))
        }
    }
}
