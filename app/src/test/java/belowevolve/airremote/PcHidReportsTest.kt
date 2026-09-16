package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class PcHidReportsTest {
    @Test fun keyboardHasBootCompatiblePayloadAndReleasesEveryKey() {
        assertArrayEquals(byteArrayOf(3, 0, 4, 0, 0, 0, 0, 0), PcHidReports.keyboard(4, 3))
        assertArrayEquals(ByteArray(8), PcHidReports.keyboard())
    }

    @Test fun mouseEncodesSignedMovementAndClampsWithoutWrapping() {
        assertArrayEquals(byteArrayOf(2, -127, 127, -1), PcHidReports.mouse(2, -500, 500, -1))
        assertArrayEquals(byteArrayOf(1, -5, 7, 0), PcHidReports.mouse(1, -5, 7))
        assertArrayEquals(ByteArray(4), PcHidReports.mouse())
    }

    @Test fun descriptorReportLengthsMatchActualWirePayloads() {
        // Parse HID short items independently: Report Size/Count/ID, Input and Output.
        val bits = mutableMapOf<Pair<Int, Int>, Int>()
        var size = 0
        var count = 0
        var id = 0
        var index = 0
        val descriptor = PcHidReports.descriptor
        while (index < descriptor.size) {
            val prefix = descriptor[index++].toInt() and 255
            val length = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
            var value = 0
            repeat(length) { value = value or ((descriptor[index++].toInt() and 255) shl (8 * it)) }
            when (prefix and 0xFC) {
                0x74 -> size = value
                0x94 -> count = value
                0x84 -> id = value
                0x80, 0x90 -> { val key = id to (prefix and 0xFC); bits[key] = (bits[key] ?: 0) + size * count }
            }
        }
        assertEquals(mapOf((1 to 0x80) to 64, (1 to 0x90) to 8, (2 to 0x80) to 32, (3 to 0x80) to 8), bits)
        assertEquals(bits[1 to 0x80], PcHidReports.keyboard().size * 8)
        assertEquals(bits[2 to 0x80], PcHidReports.mouse().size * 8)
        assertEquals(bits[3 to 0x80], PcHidReports.consumer().size * 8)
    }
}
