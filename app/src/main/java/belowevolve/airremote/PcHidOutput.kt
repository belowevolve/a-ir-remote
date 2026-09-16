package belowevolve.airremote

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One output queue per connection. Pointer samples are combined before entering Bluetooth. */
internal class PcHidOutput(private val transmit: (Int, ByteArray) -> Unit) {
    private val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private var generation = 0
    private var closed = false
    private var nextKeyAt = 0L
    private var pointerPending = false
    private var pointerVersion = 0
    private var dx = 0
    private var dy = 0
    private var wheel = 0
    private var buttons = 0

    @Synchronized fun key(code: Int, modifiers: Int): Boolean {
        if (closed || worker.queue.size >= 30) return false
        val now = System.nanoTime()
        // Keep press/release distinct, without blocking the UI thread.
        val start = maxOf(now, nextKeyAt)
        val epoch = generation
        schedule(start - now, epoch) { transmit(PcHidReports.KEYBOARD, PcHidReports.keyboard(code, modifiers)) }
        schedule(start - now + TimeUnit.MILLISECONDS.toNanos(10), epoch) { transmit(PcHidReports.KEYBOARD, PcHidReports.keyboard()) }
        nextKeyAt = start + TimeUnit.MILLISECONDS.toNanos(20)
        return true
    }

    @Synchronized fun move(x: Int, y: Int, scroll: Int) {
        if (closed) return
        dx += x; dy += y; wheel += scroll
        schedulePointer()
    }

    private fun schedulePointer() {
        if (pointerPending || (dx == 0 && dy == 0 && wheel == 0)) return
        pointerPending = true
        val epoch = generation
        val version = pointerVersion
        // At most 125 reports/s; do not flood Bluetooth with every touch sample.
        schedule(TimeUnit.MILLISECONDS.toNanos(8), epoch) {
            val report = synchronized(this) {
                if (epoch != generation || version != pointerVersion) return@schedule
                val x = dx.coerceIn(-127, 127)
                val y = dy.coerceIn(-127, 127)
                val scroll = wheel.coerceIn(-127, 127)
                dx -= x; dy -= y; wheel -= scroll
                PcHidReports.mouse(buttons, x, y, scroll)
            }
            transmit(PcHidReports.MOUSE, report)
            synchronized(this) {
                if (epoch == generation && version == pointerVersion) {
                    pointerPending = false
                    // Schedule from completion, so a slow transport cannot cause a burst.
                    schedulePointer()
                }
            }
        }
    }

    @Synchronized fun buttons(value: Int) {
        if (closed) return
        pointerVersion++
        pointerPending = false
        // Flush movement with the old button state before pressing/releasing.
        // Otherwise the delayed pointer task can turn the end of a drag into a hover.
        while (dx != 0 || dy != 0 || wheel != 0) {
            val x = dx.coerceIn(-127, 127)
            val y = dy.coerceIn(-127, 127)
            val scroll = wheel.coerceIn(-127, 127)
            dx -= x; dy -= y; wheel -= scroll
            val pending = PcHidReports.mouse(buttons, x, y, scroll)
            schedule(0, generation) { transmit(PcHidReports.MOUSE, pending) }
        }
        buttons = value
        val report = PcHidReports.mouse(value)
        schedule(0, generation) { transmit(PcHidReports.MOUSE, report) }
    }

    @Synchronized fun consumer(value: Int) {
        if (closed) return
        schedule(0, generation) { transmit(PcHidReports.CONSUMER, PcHidReports.consumer(value == 1, value == 2, value == 3)) }
        schedule(TimeUnit.MILLISECONDS.toNanos(10), generation) { transmit(PcHidReports.CONSUMER, PcHidReports.consumer()) }
    }

    @Synchronized fun release() {
        if (closed) return
        generation++
        worker.queue.clear()
        nextKeyAt = 0
        pointerPending = false
        dx = 0; dy = 0; wheel = 0; buttons = 0
        schedule(0, generation) {
            transmit(PcHidReports.KEYBOARD, PcHidReports.keyboard())
            transmit(PcHidReports.MOUSE, PcHidReports.mouse())
            transmit(PcHidReports.CONSUMER, PcHidReports.consumer())
        }
    }

    @Synchronized fun close() {
        if (closed) return
        release()
        closed = true
        worker.shutdown()
    }

    private fun schedule(delay: Long, epoch: Int, action: () -> Unit) {
        worker.schedule(
            {
                val current = synchronized(this) { epoch == generation }
                if (current) action()
            },
            delay,
            TimeUnit.NANOSECONDS,
        )
    }
}
