package belowevolve.airremote

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TvPowerSessionTest {
    @Test fun unknownOrAwakeScreenNeverGetsAnUnrequestedPowerToggle() = runBlocking {
        val power = TvPowerSession(standbyTransitionMillis = 0)
        power.ensureAwake { fail("Unknown state must not toggle an already-on TV") }
        power.update(true)
        power.ensureAwake { fail("Awake TV must not get POWER") }
    }

    @Test fun standbyYouTubeWaitsForScreenOnBeforeLaunching() = runBlocking {
        val power = TvPowerSession(standbyTransitionMillis = 0)
        power.update(false)
        val packets = mutableListOf<String>()
        val command = launch(start = CoroutineStart.UNDISPATCHED) {
            power.ensureAwake { packets += "POWER" }
            packets += "YouTube"
        }
        assertEquals(listOf("POWER"), packets)
        power.update(true)
        command.join()
        assertEquals(listOf("POWER", "YouTube"), packets)
    }

    @Test fun repeatedOffStatusAndButtonBurstShareOneWake() = runBlocking {
        val power = TvPowerSession(standbyTransitionMillis = 0)
        power.update(false)
        var toggles = 0
        var commands = 0
        val first = launch(start = CoroutineStart.UNDISPATCHED) { power.ensureAwake { toggles++ }; commands++ }
        power.update(false)
        val second = launch(start = CoroutineStart.UNDISPATCHED) { power.ensureAwake { toggles++ }; commands++ }
        assertEquals(1, toggles)
        assertEquals(0, commands)
        power.update(true)
        first.join(); second.join()
        assertEquals(2, commands)
        power.update(false)
        val third = launch(start = CoroutineStart.UNDISPATCHED) { power.ensureAwake { toggles++ } }
        assertEquals(2, toggles)
        power.update(true)
        third.join()
    }

    @Test fun explicitPowerOrIrWakeIsNotToggledAgainByYouTube() = runBlocking {
        val power = TvPowerSession(standbyTransitionMillis = 0)
        power.update(false)
        power.explicitWakeSent()
        var launched = false
        val command = launch(start = CoroutineStart.UNDISPATCHED) {
            power.ensureAwake { fail("Must not turn screen off with a second POWER") }
            launched = true
        }
        assertFalse(launched)
        power.update(true)
        command.join()
        assertTrue(launched)
    }

    @Test fun reconnectDropsOldWaitWithoutDeliveringItsCommandOrCancellingConsumer() = runBlocking {
        val power = TvPowerSession(standbyTransitionMillis = 0)
        power.update(false)
        var invalidated = false
        val command = launch(start = CoroutineStart.UNDISPATCHED) {
            try { power.ensureAwake {}; fail("Old session must not deliver its command") }
            catch (expected: IOException) { invalidated = true }
        }
        power.reset()
        command.join()
        assertTrue(invalidated)
        power.ensureAwake { fail("New session has no power state yet") }
    }

    @Test fun failedWakeSendCanBeRetried() = runBlocking {
        val power = TvPowerSession(standbyTransitionMillis = 0)
        power.update(false)
        try { power.ensureAwake { throw IOException("send failed") }; fail("Must report failure") }
        catch (expected: IOException) { }
        var retried = false
        power.ensureAwake { retried = true; power.update(true) }
        assertTrue(retried)
    }

    @Test fun missingWakeAckIsAnIoFailureNotCoroutineCancellation() = runBlocking {
        val power = TvPowerSession(timeoutMillis = 20)
        power.update(false)
        try { power.ensureAwake {}; fail("Missing screen-on acknowledgement must time out") }
        catch (expected: SocketTimeoutException) { }
        power.reset()
        power.update(true)
        power.ensureAwake { fail("Consumer must remain usable") }
    }

    @Test fun timedOutWakeDoesNotLeaveLaterCommandsWaitingOnAnOldRequest() = runBlocking {
        val power = TvPowerSession(timeoutMillis = 20)
        power.update(false)
        try { power.ensureAwake {}; fail("Missing acknowledgement must time out") }
        catch (expected: SocketTimeoutException) { }
        power.ensureAwake { power.update(true) }
    }

    @Test fun cancelledWakeDoesNotStrandTheNextCommand() = runBlocking {
        val power = TvPowerSession(timeoutMillis = 100, standbyTransitionMillis = 40)
        power.update(true)
        power.update(false)
        val command = launch(start = CoroutineStart.UNDISPATCHED) {
            power.ensureAwake { fail("Cancelled wake must not send POWER") }
        }
        command.cancelAndJoin()
        power.ensureAwake { power.update(true) }
    }

    @Test fun callerTimeoutMustRemainCancellation() = runBlocking {
        val power = TvPowerSession(timeoutMillis = 1000)
        power.update(false)
        try {
            withTimeout(20) { power.ensureAwake {} }
            fail("Caller timeout must cancel the operation")
        } catch (expected: TimeoutCancellationException) { }
    }

    @Test fun immediateWakeAllowsTheOffTransitionToFinishWithoutBlindRetoggling() = runBlocking {
        val power = TvPowerSession(standbyTransitionMillis = 40)
        power.update(true)
        val offAt = System.nanoTime()
        power.update(false)
        var sentAt = 0L
        val command = launch(start = CoroutineStart.UNDISPATCHED) {
            power.ensureAwake { sentAt = System.nanoTime(); power.update(true) }
        }
        assertEquals(0L, sentAt)
        command.join()
        assertTrue((sentAt - offAt) / 1_000_000 >= 30)
    }

    @Test fun externalWakeDuringOffTransitionPreventsAnExtraPowerToggle() = runBlocking {
        val power = TvPowerSession(standbyTransitionMillis = 20)
        power.update(true)
        power.update(false)
        val command = launch(start = CoroutineStart.UNDISPATCHED) {
            power.ensureAwake { fail("External wake must not get an extra POWER") }
        }
        power.explicitWakeSent()
        power.update(true)
        command.join()
    }

    @Test fun explicitPowerAndSleepKeysBypassAutomaticWake() {
        for (key in listOf(26, 177, 223, 224, 276)) assertFalse(TvPowerSession.shouldWakeBeforeKey(key))
        for (key in listOf(3, 4, 19, 23, 84, 164, 178, 183, 186)) assertTrue(TvPowerSession.shouldWakeBeforeKey(key))
    }
}
