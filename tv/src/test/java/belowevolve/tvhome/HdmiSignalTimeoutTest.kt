package belowevolve.tvhome

import org.junit.Assert.*
import org.junit.Test

class HdmiSignalTimeoutTest {
    @Test
    fun tuningDoesNotConsumeTheTimeToReadTheMessage() {
        val signal = HdmiSignalTimeout()
        signal.resume(100, videoAvailable = false, audioOnly = false)
        assertNull(signal.remaining(5_100))
        signal.unavailable(5_100, false) // UI is finally visible after HDMI negotiation.
        assertEquals(10_000L, signal.remaining(5_100))
        assertEquals(1L, signal.remaining(15_099))
        assertEquals(0L, signal.remaining(15_100))
    }

    @Test
    fun reconnectAndRepeatedUnavailableDoNotExtendDeadline() {
        val signal = HdmiSignalTimeout()
        signal.resume(0, false, false)
        signal.unavailable(500, false)
        signal.unavailable(10_499, false)
        assertEquals(0L, signal.remaining(10_500))
    }

    @Test
    fun recoveredVideoCancelsExitAndStartsFreshIntervalOnLaterLoss() {
        val signal = HdmiSignalTimeout()
        signal.resume(0, false, false)
        signal.unavailable(500, false)
        signal.available()
        assertNull(signal.remaining(6_000))
        signal.unavailable(10_000, false)
        assertEquals(10_000L, signal.remaining(10_000))
        assertEquals(0L, signal.remaining(20_000))
    }

    @Test
    fun audioOnlyIsPlayableAndCancelsPendingExit() {
        val signal = HdmiSignalTimeout()
        signal.resume(0, false, false)
        signal.unavailable(0, false)
        signal.unavailable(5_000, true)
        assertNull(signal.remaining(6_000))
        signal.resume(10_000, false, true)
        assertNull(signal.remaining(30_000))
    }

    @Test
    fun pausedActivityCannotReturnHomeBehindAnotherApp() {
        val signal = HdmiSignalTimeout()
        signal.resume(0, false, false)
        signal.unavailable(0, false)
        signal.pause()
        signal.unavailable(6_000, false)
        assertNull(signal.remaining(10_000))
        signal.resume(10_000, false, false, messageVisible = true)
        assertEquals(10_000L, signal.remaining(10_000))
    }

    @Test
    fun retryCancelsOldDeadlineUntilTheMessageAppearsAgain() {
        val signal = HdmiSignalTimeout()
        signal.resume(0, false, false, messageVisible = true)
        signal.available() // Retry hides the message.
        assertNull(signal.remaining(9_000))
        signal.unavailable(14_000, false)
        assertEquals(10_000L, signal.remaining(14_000))
        assertEquals(0L, signal.remaining(24_000))
    }

    @Test
    fun resumedPlayingActivityHasNoDeadline() {
        val signal = HdmiSignalTimeout()
        signal.resume(0, true, false)
        assertNull(signal.remaining(100_000))
    }
}
