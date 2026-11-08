package belowevolve.tvhome

import belowevolve.shared.design.PosterBadgeSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterBadgeSpecTest {
  @Test
  fun supportsProviderAndAlreadyFormattedSeasons() {
    for (source in listOf("5", "Сезон 5", "С5", "Season 5", "S5")) assertEquals(
      "С5 · 5 серия",
      PosterBadgeSpec.episode(source, "5 серия"),
    )
  }

  @Test
  fun missingPartsDoNotLeaveSeparators() {
    assertEquals("5 серия", PosterBadgeSpec.episode("", "5 серия"))
    assertEquals("С5", PosterBadgeSpec.episode("5", ""))
    assertEquals("", PosterBadgeSpec.episode(null, null))
    assertEquals("Спецвыпуск", PosterBadgeSpec.season("Спецвыпуск"))
  }
}
