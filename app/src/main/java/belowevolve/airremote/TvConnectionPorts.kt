package belowevolve.airremote

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** TLS may reject a client on its first read, after startHandshake has returned. */
internal class TvConnectionPorts {
    private val working = ConcurrentHashMap<String, Int>()

    fun connect(host: String, attempt: (Int, () -> Unit) -> Unit) {
        val preferred = working[host] ?: 6465
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
