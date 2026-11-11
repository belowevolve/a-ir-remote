package belowevolve.airremote

import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoiceWakeRecoveryTest {
    @Test fun infraredNeverSendsASecondToggleAfterAnUnconfirmedPulse() = runBlocking {
        var attempts = 0
        try {
            ensureVoiceAwake({ attempts++; throw SocketTimeoutException() }, { false }, retryWake = false)
            fail("Missing IR acknowledgement must be reported")
        } catch (_: SocketTimeoutException) { }
        assertEquals(1, attempts)
    }
    @Test fun normalAckDoesNotReconnectOrSendAnotherKey() = runBlocking {
        val attempts = mutableListOf<Long>()
        ensureVoiceAwake({ attempts += it }, { fail("Unexpected power refresh"); false })
        assertEquals(listOf(1_500L), attempts)
    }

    @Test fun missingAckFromAnAwakeTvDoesNotToggleItOff() = runBlocking {
        var attempts = 0
        var refreshes = 0
        ensureVoiceAwake({ attempts++; throw SocketTimeoutException() }, { refreshes++; true })
        assertEquals(1, attempts)
        assertEquals(1, refreshes)
    }

    @Test fun ignoredWakeCanRetryOnlyAfterAConfirmedOffSnapshot() = runBlocking {
        val events = mutableListOf<String>()
        ensureVoiceAwake({ timeout ->
            events += "wake:$timeout"
            if (events.size == 1) throw SocketTimeoutException()
        }, { events += "confirmed-off"; false })
        assertEquals(listOf("wake:1500", "confirmed-off", "wake:5000"), events)
    }

    @Test fun aSecondMissingAckIsReportedWithoutAnEndlessPowerLoop() = runBlocking {
        var attempts = 0
        try {
            ensureVoiceAwake({ attempts++; throw SocketTimeoutException() }, { false })
            fail("Must report the second timeout")
        } catch (_: SocketTimeoutException) { }
        assertEquals(2, attempts)
    }

    @Test fun cancellationNeverRetriesPower() = runBlocking {
        try {
            ensureVoiceAwake({ throw CancellationException() }, { fail("Cancelled wake was retried"); false })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}
