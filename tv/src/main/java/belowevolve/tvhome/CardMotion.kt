package belowevolve.tvhome

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/** One focus progress drives scale, lift and glow, without springs or idle animation loops. */
object CardMotion {
  const val MaxScale = 1.06f
  val NAMES = arrayOf("Мягкая", "Подъём", "Свечение")
  private val soft = CubicBezierEasing(.25f, .1f, .25f, 1f)
  private val smooth = CubicBezierEasing(.2f, 0f, 0f, 1f)

  fun scale(mode: Int): Float =
    when (mode) {
      1 -> 1.03f
      2 -> 1f
      else -> MaxScale
    }

  fun lift(mode: Int): Float = if (mode == 1) -4f else 0f

  fun duration(mode: Int, focused: Boolean): Int =
    when (mode) {
      1 -> if (focused) 220 else 180
      2 -> if (focused) 240 else 200
      else -> if (focused) 240 else 180
    }

  fun easing(mode: Int): Easing = if (mode == 0) soft else smooth
}
