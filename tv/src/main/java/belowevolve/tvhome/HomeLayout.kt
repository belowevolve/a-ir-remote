package belowevolve.tvhome

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

/* Shared geometry for Home, previews and wallpaper treatment. Distances are dp. */
class HomeLayout(c: Context) {
  val mode: Int
  val bottom: Int
  val posterTop: Int
  val captionStyle: Int
  val motion: Int
  val scrollMode: Int
  val focusSlot: Int
  val gap: Int
  val size: Int
  val posterWidth: Int
  val posterHeight: Int
  val glow: Int
  val shadow: Int
  val shadowX: Int
  val shadowY: Int
  val shadowSize: Int
  val shadowBlur: Int
  val showTitle: Boolean
  val showEpisodeBadges: Boolean
  val badgeVisibility: Int
  val badgeFormat: Int
  val showNewsBadges: Boolean
  val captionGap: Int
  val animateTitle: Boolean
  val cornerRadius: Float
  val badgeInset: Float

  init {
    val p: SharedPreferences = c.getSharedPreferences("appearance", 0)
    mode = clamp(p.getInt("layout", 0), 0, 1)
    scrollMode = clamp(p.getInt("poster_scroll", 0), 0, SCROLL_MODES.lastIndex)
    focusSlot = if (scrollMode == 1) 0 else 2
    gap = clamp(p.getInt("poster_gap", PosterGap), 8, 32)
    size = clamp(p.getInt("poster_size", 1), 0, 2)
    posterWidth = cardWidth(size, gap)
    posterHeight = (posterWidth * 1.5f).roundToInt()
    cornerRadius = posterCorner(posterWidth)
    badgeInset = posterBadgeInset(posterWidth)
    badgeVisibility =
      clamp(p.getInt("poster_badges_mode", if (p.getBoolean("poster_badges", true)) 0 else 2), 0, 2)
    showEpisodeBadges = badgeVisibility == 0
    showNewsBadges = badgeVisibility != 2
    badgeFormat =
      clamp(
        p.getInt(
          "poster_badge_style",
          p.getInt("poster_badge_format", 0).takeIf { it in 0..1 } ?: 0,
        ),
        0,
        belowevolve.shared.design.PosterBadgeSpec.FORMATS.lastIndex,
      )
    captionGap = clamp(p.getInt("caption_gap", 24), 8, 40)
    animateTitle = p.getBoolean("animate_title", true)
    glow = clamp(p.getInt("poster_glow", 100), 0, 100)
    shadow = clamp(p.getInt("poster_shadow", 0), 0, PosterShadow.NAMES.lastIndex)
    shadowX = clamp(p.getInt("volume_shadow_x", 0), -32, 32)
    shadowY = clamp(p.getInt("volume_shadow_y", PosterShadow.DefaultY), -32, 40)
    shadowSize = clamp(p.getInt("volume_shadow_size", 100), 70, 140)
    shadowBlur = clamp(p.getInt("volume_shadow_blur", 10), 2, 24)
    bottom = clamp(p.getInt("bottom_inset", 32), 24, 160)
    posterTop = if (mode == 0) 194 else 540 - bottom - posterHeight
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

  internal fun shadowStyle(wide: Boolean) =
    PosterShadow.Style(
      shadow,
      wide,
      if (shadow == 0) shadowSize else 100,
      if (shadow == 0) shadowBlur else 10,
    )

  companion object {
    // Defaults also define the reusable artwork/shadow template, independent of the size preset.
    const val PosterWidth = 132
    const val CornerRadius = 12f
    const val PosterHeight = 198
    const val PosterGap = 12
    const val RowInset = 32
    const val RowStep = PosterWidth + PosterGap
    const val MaxPosterWidth = 162
    const val MaxPosterHeight = MaxPosterWidth * 3 / 2
    val SIZES = arrayOf("Компактные", "Средние", "Крупные")
    val BADGE_VISIBILITY = arrayOf("Все", "Только новые", "Скрывать")
    val SCROLL_MODES = arrayOf("По ленте", "Выбранная слева")
    val NAMES: Array<String> = arrayOf<String>("Лента по центру", "Нижняя полка")
    val CAPTION_IDS: IntArray = intArrayOf(0, 1, 4, 3)
    val CAPTIONS: Array<String> =
      arrayOf<String>("Название", "Название и серия", "В одну строку", "Без подписи")
    val MOTIONS: Array<String> = CardMotion.NAMES

    fun posterCorner(width: Int) = CornerRadius * width / PosterWidth

    // Row adds its normal item gap before this final spacer too.
    fun firstPositionTail(width: Int, gap: Int) =
      (960 - 2 * RowInset - width - gap).coerceAtLeast(0)

    fun posterBadgeInset(width: Int) =
      maxOf(4f, posterCorner(width) - belowevolve.shared.design.PosterBadgeSpec.CORNER_DP)

    fun cardWidth(size: Int, gap: Int): Int {
      val fullCards = 7 - size.coerceIn(0, 2)
      return ((960f - RowInset - fullCards * gap) / (fullCards + .5f)).roundToInt()
    }

    private fun clamp(v: Int, min: Int, max: Int): Int {
      return Math.max(min, Math.min(max, v))
    }
  }
}
