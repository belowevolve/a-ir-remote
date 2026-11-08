package belowevolve.airremote

import org.junit.Assert.assertThrows
import org.junit.Test

class ClipboardTextTest {
    @Test fun acceptsUnicodeAndMultilineTextAtTheUtf8Limit() {
        ClipboardText.validate("Привет 👋\n\"one\"\t二\r\n")
        for (text in listOf("a".repeat(65536), "я".repeat(32768), "👋".repeat(16384))) {
            ClipboardText.validate(text)
            assertThrows(IllegalArgumentException::class.java) { ClipboardText.validate(text + "a") }
        }
    }

    @Test fun rejectsEmptyNulAndMalformedUtf16RatherThanChangingText() {
        for (text in listOf("", "before\u0000after", "\ud800", "\udc00", "\ud800x")) {
            assertThrows(IllegalArgumentException::class.java) { ClipboardText.validate(text) }
        }
    }
}
