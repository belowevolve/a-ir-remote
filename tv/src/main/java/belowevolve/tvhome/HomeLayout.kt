package belowevolve.tvhome

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

/* Shared geometry for Home, previews and wallpaper treatment. Distances are dp. */
class HomeLayout(c: Context) {
  val mode: Int
  val bottom: Int
  val rowTop: Int
  val posterTop: Int
  val captionStyle: Int
  val motion: Int
  val gap: Int
  val size: Int
  val posterWidth: Int
  val posterHeight: Int
  val glow: Int
  val showTitle: Boolean

  init {
    val p: SharedPreferences = c.getSharedPreferences("appearance", 0)
    mode = clamp(p.getInt("layout", 0), 0, 1)
    gap = clamp(p.getInt("poster_gap", PosterGap), 8, 32)
    size = clamp(p.getInt("poster_size", 1), 0, 2)
    posterWidth = cardWidth(size, gap)
    posterHeight = (posterWidth * 1.5f).roundToInt()
    glow = clamp(p.getInt("poster_glow", 100), 0, 100)
    bottom = clamp(p.getInt("bottom_inset", 32), 24, 160)
    posterTop = if (mode == 0) 194 else 540 - bottom - posterHeight
    rowTop = posterTop - 16
    val savedCaption: Int =
      p.getInt("caption_style", if (p.getBoolean("show_title", true)) 1 else 3)
    // Preserve IDs: retired badges become inline text; the old panel becomes two lines.
    captionStyle =
      if (savedCaption == 5) 4
      else if (savedCaption == 2 || savedCaption < 0 || savedCaption > 4) 1 else savedCaption
    showTitle = captionStyle != 3
    // Retired effects migrate to Soft. The new key avoids reinterpreting old IDs.
    motion = clamp(p.getInt("card_motion_v2", 0), 0, CardMotion.NAMES.lastIndex)
  }

  fun captionIndex(): Int {
    for (i: Int in CAPTION_IDS.indices) if (CAPTION_IDS[i] == captionStyle) return i
    return 1
  }

  companion object {
    // Defaults also define the reusable artwork/shadow template, independent of the size preset.
    const val PosterWidth = 132
    const val PosterHeight = 198
    const val PosterGap = 12
    const val RowInset = 32
    const val RowStep = PosterWidth + PosterGap
    const val MaxPosterWidth = 162
    const val MaxPosterHeight = MaxPosterWidth * 3 / 2
    val SIZES = arrayOf("Компактные", "Средние", "Крупные")
    val NAMES: Array<String> = arrayOf<String>("Лента по центру", "Нижняя полка")
    val CAPTION_IDS: IntArray = intArrayOf(0, 1, 4, 3)
    val CAPTIONS: Array<String> =
      arrayOf<String>("Название", "Название и серия", "В одну строку", "Без подписи")
    val MOTIONS: Array<String> = CardMotion.NAMES

    fun cardWidth(size: Int, gap: Int): Int {
      val fullCards = 7 - size.coerceIn(0, 2)
      return ((960f - RowInset - fullCards * gap) / (fullCards + .5f)).roundToInt()
    }

    private fun clamp(v: Int, min: Int, max: Int): Int {
      return Math.max(min, Math.min(max, v))
    }
  }
}
