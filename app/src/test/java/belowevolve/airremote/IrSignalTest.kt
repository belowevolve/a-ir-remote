package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class IrSignalTest {
    @Test fun haierProfileEncodesNecAddressAndCommandWithComplements() {
        val (frequency, pattern) = IrSignal.parse(IrSignal.haierPower())
        assertEquals(38000, frequency)
        assertEquals(67, pattern.size)
        assertArrayEquals(intArrayOf(9000, 4500), pattern.take(2).toIntArray())
        val bytes = (0..3).map { byte ->
            (0..7).sumOf { bit -> if (pattern[3 + (byte * 8 + bit) * 2] > 1000) 1 shl bit else 0 }
        }
        assertEquals(listOf(4, 251, 8, 247), bytes)
        assertEquals(560, pattern.last())
    }
    @Test fun malformedAndOverlongSignalsAreRejected() {
        for (raw in listOf("", "38000 10", "0 1 2 3", "38000 -1 2 3", "38000 " + List(30) { "100000" }.joinToString(" "))) {
            assertThrows(IllegalArgumentException::class.java) { IrSignal.parse(raw) }
        }
    }
}
