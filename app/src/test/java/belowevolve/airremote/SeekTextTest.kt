package belowevolve.airremote

import belowevolve.shared.design.SeekText
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekTextTest {
    @Test fun formatsSecondsMinutesAndHoursWithoutLosingTheRemainder() {
        for ((seconds, text) in mapOf(0 to "0 сек", 5 to "5 сек", 60 to "1 мин", -90 to "1 мин 30 сек",
            900 to "15 мин", 3600 to "1 ч", 6000 to "1 ч 40 мин"))
            assertEquals(text, SeekText.duration(seconds))
    }
    @Test fun separatesSendingFromAnAcceptedSeekAndKeepsDirection() {
        assertEquals("Отправлена перемотка на 5 мин назад", SeekText.sent(-300))
        assertEquals("Перемотка: 15 мин вперёд", SeekText.accepted(900))
    }
}
