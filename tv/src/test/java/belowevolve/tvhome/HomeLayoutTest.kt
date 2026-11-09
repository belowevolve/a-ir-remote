package belowevolve.tvhome

import belowevolve.shared.design.PosterBadgeSpec
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class HomeLayoutTest {
  @Test
  fun firstPositionModeHasEnoughRoomToAnchorTheLastCard() {
    for (size in 0..2) for (gap in 8..32 step 4) for (count in 1..16) {
      val width = HomeLayout.cardWidth(size, gap)
      val content =
        2 * HomeLayout.RowInset +
          count * width +
          count * gap +
          HomeLayout.firstPositionTail(width, gap)
      assertEquals((count - 1) * (width + gap), content - 960)
    }
  }

  @Test
  fun keepsCurrentDefaults() {
    assertEquals(132, HomeLayout.cardWidth(1, 12))
  }

  @Test
  fun everySizeAndSpacingLeavesAUsefulHalfCard() {
    for (size in 0..2) for (gap in 8..32 step 4) {
      val width = HomeLayout.cardWidth(size, gap)
      val wholeCards = 7 - size
      val visibleNext = 960 - HomeLayout.RowInset - wholeCards * (width + gap)
      assertTrue(
        "size=$size gap=$gap leaves only $visibleNext of $width",
        visibleNext in (width * .4f).toInt()..(width * .6f).toInt(),
      )
      assertTrue(width <= HomeLayout.MaxPosterWidth)
    }
  }

  @Test
  fun badgeCornerKeepsItsInsetInsideEveryCardSize() {
    for (size in 0..2) for (gap in 8..32 step 4) {
      val width = HomeLayout.cardWidth(size, gap)
      val outer = HomeLayout.posterCorner(width)
      val inset = HomeLayout.posterBadgeInset(width)
      val inner = PosterBadgeSpec.CORNER_DP.toFloat()
      for (step in 0..90) {
        val angle = Math.PI + step * Math.PI / 180
        val x = inset + inner + inner * cos(angle)
        val y = inset + inner + inner * sin(angle)
        val distance =
          if (x < outer && y < outer) outer - hypot(x - outer, y - outer) else minOf(x, y)
        assertTrue(
          "size=$size gap=$gap angle=$step leaves $distance of $inset",
          distance >= inset - .001,
        )
      }
    }
  }
}
