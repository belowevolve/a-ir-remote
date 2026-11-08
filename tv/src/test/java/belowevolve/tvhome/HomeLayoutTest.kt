package belowevolve.tvhome

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutTest {
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
}
