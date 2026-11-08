package belowevolve.airremote

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.InputStream
import javax.net.ssl.SSLSocket
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One foreground-bound TLS session, never retried or resumed after a failed ACK. */
internal class PcTextTransport {
    private val lock = Any()
    private var cancelled = false
    private var socket: SSLSocket? = null
    private var input: InputStream? = null

    fun cancel() {
        val old = synchronized(lock) { cancelled = true; socket.also { socket = null } }
        Dispatchers.IO.dispatch(EmptyCoroutineContext) { runCatching { old?.close() } }
    }

    private suspend fun <T> io(action: () -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            try {
                synchronized(lock) { check(!cancelled) { "cancelled" } }
                val result = action()
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    suspend fun open(endpoint: CompanionEndpoint, network: Network): PcTextSnapshot = io {
        val connection = endpoint.open(network, "text_input_v4") { pending ->
            synchronized(lock) {
                if (cancelled) { pending.close(); error("cancelled") }
                socket = pending
            }
        }
        synchronized(lock) {
            check(!cancelled) { "cancelled" }
            input = connection.inputStream.buffered()
        }
        val initial = JSONObject(CompanionEndpoint.readLine(checkNotNull(input), PcTextSnapshot.MAX_BYTES * 6 + 256))
        check(initial.getLong("revision") == 0L) { "invalid_ack" }
        PcTextSnapshot(initial.getString("text"), initial.getInt("start"), initial.getInt("end")).also { it.validate() }
    }

    suspend fun send(frame: PcTextFrame): PcTextAck = io {
        val connection = synchronized(lock) { checkNotNull(socket) }
        val request = JSONObject().put("revision", frame.revision)
        if (frame.key != null) request.put("key", frame.key).put("count", frame.count)
        else {
            frame.snapshot.validate()
            request.put("text", frame.snapshot.text).put("start", frame.snapshot.start).put("end", frame.snapshot.end)
        }
        val bytes = (request.toString() + "\n").toByteArray(Charsets.UTF_8)
        check(bytes.size <= PcTextSnapshot.MAX_BYTES * 6 + 256) { "text_too_large" }
        connection.outputStream.write(bytes)
        connection.outputStream.flush()
        val response = JSONObject(CompanionEndpoint.readLine(checkNotNull(input), PcTextSnapshot.MAX_BYTES * 6 + 256))
        val revision = response.getLong("revision")
        check(revision == frame.revision) { "invalid_ack" }
        check(response.getBoolean("ok")) { response.optString("error", "input_failed") }
        val context = if (response.has("text")) PcTextSnapshot(response.getString("text"), response.getInt("start"), response.getInt("end")).also { it.validate() } else null
        check(frame.key == null || context != null) { "invalid_ack" }
        PcTextAck(revision, context)
    }
}
