package belowevolve.airremote

import java.io.IOException

/** The TV can report power before InputReader finishes adding its virtual remote. */
internal class TvKeyStartupGate(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val lock = Any()
    private var session: Any? = null
    private var readyAt: Long? = null

    fun open(token: Any) = synchronized(lock) {
        session = token
        readyAt = null
    }

    fun ready(token: Any) = synchronized(lock) {
        if (session === token && readyAt == null) readyAt = nowMillis() + 100
    }

    fun close() = synchronized(lock) {
        session = null
        readyAt = null
    }

    fun awaitReady(token: Any) {
        while (true) {
            val remaining = synchronized(lock) {
                if (session !== token) throw IOException("Соединение с ТВ изменилось")
                readyAt?.let { (it - nowMillis()).coerceAtLeast(0) } ?: 0
            }
            if (remaining == 0L) return
            // Neither the gate lock nor TvClient's writeLock is held while waiting.
            sleepMillis(remaining)
        }
    }
}
