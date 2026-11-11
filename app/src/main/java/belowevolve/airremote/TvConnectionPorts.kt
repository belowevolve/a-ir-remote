package belowevolve.airremote

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** TLS may reject a client on its first read, after startHandshake has returned. */
internal class TvConnectionPorts(private val initialPort: Int = 6466) {
    init { require(initialPort == 6465 || initialPort == 6466) }
    private val working = ConcurrentHashMap<String, Int>()

    fun connect(host: String, preferredPort: Int? = null, attempt: (Int, () -> Unit) -> Unit) {
        // Google's 6465 wake server remains reachable in Low Power Standby;
        // the normal 6466 control endpoint sends periodic keepalives.
        require(preferredPort == null || preferredPort == 6465 || preferredPort == 6466)
        val preferred = preferredPort ?: working[host] ?: initialPort
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
