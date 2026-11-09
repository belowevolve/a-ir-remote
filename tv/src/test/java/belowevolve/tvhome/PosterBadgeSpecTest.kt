package belowevolve.tvhome

import belowevolve.shared.design.PosterBadgeSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterBadgeSpecTest {
  @Test
  fun supportsProviderAndAlreadyFormattedSeasons() {
    for (source in listOf("5", "Сезон 5", "С5", "Season 5", "S5")) assertEquals(
      "5",
      PosterBadgeSpec.seasonNumber(source),
    )
  }

  @Test
  fun missingPartsDoNotLeaveSeparators() {
    assertEquals("5 серия", PosterBadgeSpec.format("", "5 серия", PosterBadgeSpec.CIRCLE))
    assertEquals("5 сезон", PosterBadgeSpec.format("5", "", PosterBadgeSpec.CIRCLE))
    assertEquals("", PosterBadgeSpec.format(null, null, PosterBadgeSpec.CIRCLE))
    assertEquals("Спецвыпуск", PosterBadgeSpec.season("Спецвыпуск"))
  }

  @Test
  fun formatsSeasonAndEpisodeWithoutInventingMissingNumbers() {
    val expected = listOf("7 серия", "7 серия", "s07e07", "S07E07", "7×07", "07×07")
    expected.forEachIndexed { format, value ->
      assertEquals(value, PosterBadgeSpec.format("Сезон 07", "Серия 07", format))
    }
    assertEquals("7", PosterBadgeSpec.circleSeason("Сезон 07", "Серия 07", PosterBadgeSpec.CIRCLE))
    assertEquals("", PosterBadgeSpec.circleSeason("7", "7 серия", PosterBadgeSpec.EPISODE))
    assertEquals("0×05", PosterBadgeSpec.format("0", "5", PosterBadgeSpec.MULTIPLY))
    assertEquals("12×105", PosterBadgeSpec.format("12", "105", PosterBadgeSpec.MULTIPLY))
    for (format in 0..5) {
      assertEquals("7 серия", PosterBadgeSpec.format("", "7 серия", format))
      assertEquals("", PosterBadgeSpec.format(null, null, format))
    }
    assertEquals(
      "7 сезон · Спецвыпуск",
      PosterBadgeSpec.format("7", "Спецвыпуск", PosterBadgeSpec.UPPERCASE),
    )
  }

  @Test
  fun headingsPutNumbersBeforeSeasonAndEpisode() {
    for (source in listOf("7", "Сезон 7", "С7", "Season 7", "S07", "7 сезон")) assertEquals(
      "7 сезон",
      PosterBadgeSpec.seasonLabel(source),
    )
    for (source in listOf("7", "Серия 7", "7 серия", "Episode 7", "E07")) assertEquals(
      "7 серия",
      PosterBadgeSpec.episodeLabel(source),
    )
    assertEquals("Спецвыпуск", PosterBadgeSpec.seasonLabel("Спецвыпуск"))
  }
}
