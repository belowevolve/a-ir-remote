package belowevolve.tvhome

import org.junit.Assert.assertEquals
import org.junit.Test

class HdmiMenuTest {
  @Test
  fun prefersWorkingLastInputThenOtherLiveInput() {
    val off = HdmiChoice("1", "HDMI 1", false, true)
    val live = HdmiChoice("2", "HDMI 2", true, false)
    assertEquals(1, preferredHdmiIndex(listOf(off, live)))
    assertEquals(0, preferredHdmiIndex(listOf(off.copy(connected = true), live)))
  }

  @Test
  fun keepsLastPortAvailableWhenAllInputsAreOff() {
    assertEquals(
      1,
      preferredHdmiIndex(
        listOf(HdmiChoice("1", "HDMI 1", false, false), HdmiChoice("2", "HDMI 2", false, true))
      ),
    )
    assertEquals(0, preferredHdmiIndex(emptyList()))
  }
}
