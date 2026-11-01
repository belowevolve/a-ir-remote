package belowevolve.airremote

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** TLS may reject a client on its first read, after startHandshake has returned. */
internal class TvConnectionPorts {
    private val working = ConcurrentHashMap<String, Int>()

    fun connect(host: String, attempt: (Int, () -> Unit) -> Unit) {
        // 6466 is the normal control endpoint. 6465 is a vendor wake endpoint
        // and can accept a session without sending periodic keepalives.
        val preferred = working[host] ?: 6466
        var established = false
        try {
            attempt(preferred) {
                established = true
                working[host] = preferred
            }
        } catch (error: IOException) {
            if (established) throw error
            val fallback = if (preferred == 6465) 6466 else 6465
            attempt(fallback) { working[host] = fallback }
        }
    }
}
