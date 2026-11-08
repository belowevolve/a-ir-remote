package belowevolve.tvhome

import org.junit.Assert.*
import org.junit.Test

class HdmiSignalTimeoutTest {
    @Test
    fun initialTuningExpiresAfterSixSeconds() {
        val signal = HdmiSignalTimeout()
        signal.resume(100, videoAvailable = false, audioOnly = false)
        assertEquals(1L, signal.remaining(6_099))
        assertEquals(0L, signal.remaining(6_100))
    }

    @Test
    fun reconnectAndRepeatedUnavailableDoNotExtendDeadline() {
        val signal = HdmiSignalTimeout()
        signal.resume(0, false, false)
        signal.unavailable(500, false)
        signal.unavailable(5_999, false)
        assertEquals(0L, signal.remaining(6_000))
    }

    @Test
    fun recoveredVideoCancelsExitAndStartsFreshIntervalOnLaterLoss() {
        val signal = HdmiSignalTimeout()
        signal.resume(0, false, false)
        signal.available()
        assertNull(signal.remaining(6_000))
        signal.unavailable(10_000, false)
        assertEquals(6_000L, signal.remaining(10_000))
        assertEquals(0L, signal.remaining(16_000))
    }

    @Test
    fun audioOnlyIsPlayableAndCancelsPendingExit() {
        val signal = HdmiSignalTimeout()
        signal.resume(0, false, false)
        signal.unavailable(5_000, true)
        assertNull(signal.remaining(6_000))
        signal.resume(10_000, false, true)
        assertNull(signal.remaining(30_000))
    }

    @Test
    fun pausedActivityCannotReturnHomeBehindAnotherApp() {
        val signal = HdmiSignalTimeout()
        signal.resume(0, false, false)
        signal.pause()
        signal.unavailable(6_000, false)
        assertNull(signal.remaining(10_000))
        signal.resume(10_000, false, false)
        assertEquals(6_000L, signal.remaining(10_000))
    }

    @Test
    fun resumedPlayingActivityHasNoDeadline() {
        val signal = HdmiSignalTimeout()
        signal.resume(0, true, false)
        assertNull(signal.remaining(100_000))
    }
}
