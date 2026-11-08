package belowevolve.airremote

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class TvKeyStartupGateTest {
    @Test fun firstKeyWaitsForInputDeviceButLaterCommandsHaveNoAddedDelay() {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val gate = TvKeyStartupGate({ now }, { waits += it; now += it })
        val socket = Any()
        gate.open(socket)
        gate.ready(socket)
        now += 30
        gate.awaitReady(socket)
        assertEquals(listOf(70L), waits)
        repeat(10) { gate.awaitReady(socket) }
        assertEquals(listOf(70L), waits)
    }

    @Test fun laterPowerReportsDoNotExtendStartupOrDelayRelease() {
        var now = 0L
        val waits = mutableListOf<Long>()
        val gate = TvKeyStartupGate({ now }, { waits += it; now += it })
        val socket = Any()
        gate.open(socket)
        gate.ready(socket)
        now = 60
        gate.ready(socket)
        gate.awaitReady(socket)
        assertEquals(listOf(40L), waits)
        now = 5_000
        gate.ready(socket)
        gate.awaitReady(socket)
        assertEquals(listOf(40L), waits)
    }

    @Test fun closeDuringWaitCancelsPendingKey() {
        lateinit var gate: TvKeyStartupGate
        gate = TvKeyStartupGate({ 0 }, { gate.close() })
        val socket = Any()
        gate.open(socket)
        gate.ready(socket)
        assertThrows(IOException::class.java) { gate.awaitReady(socket) }
    }

    @Test fun replacementSessionRejectsOldKeyAndGetsItsOwnStartupWindow() {
        var now = 0L
        var replace = true
        val old = Any()
        val fresh = Any()
        val waits = mutableListOf<Long>()
        lateinit var gate: TvKeyStartupGate
        gate = TvKeyStartupGate({ now }, {
            waits += it
            now += it
            if (replace) {
                replace = false
                gate.open(fresh)
                gate.ready(fresh)
            }
        })
        gate.open(old)
        gate.ready(old)
        assertThrows(IOException::class.java) { gate.awaitReady(old) }
        now += 20
        gate.ready(old) // A buffered callback from the previous socket must not reset the deadline.
        gate.awaitReady(fresh)
        assertEquals(listOf(100L, 80L), waits)
        assertThrows(IOException::class.java) { gate.awaitReady(old) }
    }
}
