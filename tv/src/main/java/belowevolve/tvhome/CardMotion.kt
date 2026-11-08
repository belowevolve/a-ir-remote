package belowevolve.tvhome

import android.animation.TimeInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/* Bounded focus transitions. No neighboring cards, idle loops or layout animations. */
object CardMotion {
    const val MaxScale = 1.075f
    private val CURRENT: TimeInterpolator = PathInterpolator(.2f, 0f, 0f, 1f)
    private val SOFT: TimeInterpolator = PathInterpolator(.25f, .1f, .25f, 1f)
    private val QUICK: TimeInterpolator = PathInterpolator(.2f, .8f, .2f, 1f)
    private val SPRING: TimeInterpolator = OvershootInterpolator(2f)

    fun scale(mode: Int): Float {
        return if (mode == 4) 1f else if (mode == 0) MaxScale else 1.06f
    }

    fun duration(mode: Int, focused: Boolean): Int {
        if (mode == 1) return if (focused) 240 else 180
        if (mode == 2) return if (focused) 110 else 100
        if (mode == 3) return if (focused) 280 else 160
        return if (mode == 4) 0 else 150
    }

    fun interpolator(mode: Int, focused: Boolean): TimeInterpolator {
        if (mode == 1) return SOFT
        if (mode == 2) return QUICK
        if (mode == 3) return if (focused) SPRING else CURRENT
        return CURRENT
    }
}
