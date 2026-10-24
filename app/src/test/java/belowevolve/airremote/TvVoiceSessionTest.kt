package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class TvVoiceSessionTest {
    @Test fun tvInitiatedDictationCanStartWithoutSearchKey() {
        val voice = TvVoiceSession()
        voice.request(7)
        assertEquals(7, voice.begin())
        assertEquals(7, voice.active)
        assertNull(voice.requested)
        assertFalse(voice.end(6))
        assertEquals(7, voice.active)
        assertTrue(voice.end(7))
        assertNull(voice.active)
    }
    @Test fun replacementAndDisconnectInvalidateOldAudio() {
        val voice = TvVoiceSession()
        voice.request(1); voice.begin()
        voice.request(2)
        assertNull(voice.active)
        assertFalse(voice.end(1))
        assertEquals(2, voice.begin())
        voice.reset()
        assertNull(voice.active)
        assertNull(voice.requested)
    }
    @Test(expected = IllegalStateException::class) fun cannotStartAfterTvClosedInput() {
        val voice = TvVoiceSession()
        voice.request(1); voice.end(1); voice.begin()
    }
}
