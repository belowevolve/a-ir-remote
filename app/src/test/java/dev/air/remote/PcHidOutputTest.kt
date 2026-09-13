package dev.air.remote

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PcHidOutputTest {
    @Test fun keyboardSendsOrderedPressAndReleaseOffCallerThread() {
        val reports = Collections.synchronizedList(mutableListOf<ByteArray>())
        val done = CountDownLatch(4)
        val caller = Thread.currentThread()
        val output = PcHidOutput { id, data ->
            assertNotSame(caller, Thread.currentThread())
            if (id == PcHidReports.KEYBOARD) { reports.add(data); done.countDown() }
        }
        try {
            output.key(4, 1)
            output.key(5, 0)
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(4, 0, 5, 0), reports.take(4).map { it[2].toInt() })
            assertEquals(1, reports.first()[0].toInt())
        } finally { output.close() }
    }

    @Test fun pointerSamplesProduceNonzeroMouseReports() {
        val reports = Collections.synchronizedList(mutableListOf<ByteArray>())
        val sent = CountDownLatch(1)
        val output = PcHidOutput { id, bytes ->
            if (id == PcHidReports.MOUSE) { reports.add(bytes); sent.countDown() }
        }
        try {
            output.move(25, -17, 2)
            assertTrue(sent.await(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(0, 25, -17, 2), reports.first())
        } finally { output.close() }
    }

    @Test fun releaseDropsQueuedKeysEvenWhenBluetoothIsBlocked() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val released = CountDownLatch(2)
        val reports = Collections.synchronizedList(mutableListOf<Pair<Int, ByteArray>>())
        val output = PcHidOutput { id, bytes ->
            if (id == 1 && bytes[2].toInt() == 4) {
                entered.countDown()
                unblock.await(2, TimeUnit.SECONDS)
            }
            reports.add(id to bytes)
            if (bytes.all { it == 0.toByte() }) released.countDown()
        }
        try {
            output.key(4, 0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            output.key(5, 0)
            output.release() // Must not wait for the blocked transport.
            unblock.countDown()
            assertTrue(released.await(2, TimeUnit.SECONDS))
            assertFalse(reports.any { (id, bytes) -> id == 1 && bytes[2].toInt() == 5 })
        } finally { unblock.countDown(); output.close() }
    }
}
