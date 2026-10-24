package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class TvVoiceSessionTest {
    @Test fun voiceWireFormatIsDirectional() {
        val pcm = byteArrayOf(8, 1, 18, 0)
        val message = remote.Remotemessage.RemoteVoiceClientMessage.newBuilder()
            .setRemoteVoicePayload(com.google.protobuf.ByteString.copyFrom(pcm)).build()
        // Field 31, length 4, raw PCM. No nested session ID or samples tags.
        assertArrayEquals(byteArrayOf(0xfa.toByte(), 1, 4, 8, 1, 18, 0), message.toByteArray())
        // TV -> phone field 31 is instead a nested session-end message.
        val ended = remote.Remotemessage.RemoteMessage.parseFrom(byteArrayOf(0xfa.toByte(), 1, 2, 8, 7))
        assertTrue(ended.hasRemoteVoiceSessionEnd())
        assertEquals(7, ended.remoteVoiceSessionEnd.sessionId)
    }

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
