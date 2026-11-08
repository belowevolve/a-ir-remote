package belowevolve.airremote

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import javax.net.ssl.SSLSocket
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object ClipboardText {
    const val MAX_BYTES = 64 * 1024
    const val CAPABILITY = "clipboard_text_v1"

    fun validate(text: String) {
        require(text.isNotEmpty()) { "В буфере нет текста" }
        require(text.length <= MAX_BYTES) { "Текст больше 64 КБ" }
        require('\u0000' !in text) { "Текст содержит неподдерживаемый символ" }
        // Reject malformed UTF-16 rather than silently replacing surrogate characters.
        val bytes = try {
            Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Некорректный текст в буфере")
        }
        require(bytes.remaining() <= MAX_BYTES) { "Текст больше 64 КБ" }
    }
}

/** A single user-initiated request. Cancellation closes its socket; it is never retried. */
internal class ClipboardTransfer {
    private val lock = Any()
    private var cancelled = false
    private var socket: SSLSocket? = null

    fun cancel() {
        val old = synchronized(lock) {
            cancelled = true
            socket.also { socket = null }
        }
        Dispatchers.IO.dispatch(EmptyCoroutineContext) { runCatching { old?.close() } }
    }

    suspend fun send(endpoint: CompanionEndpoint, network: Network, text: String): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            try {
                val result = exchange(endpoint, network, text)
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private fun exchange(endpoint: CompanionEndpoint, network: Network, text: String): String {
        ClipboardText.validate(text)
        val connection = endpoint.open(network, "clipboard") { pending ->
            synchronized(lock) {
                check(!cancelled) { "Передача отменена" }
                socket = pending
            }
        }
        try {
            synchronized(lock) { check(!cancelled) { "Передача отменена" } }
            val request = JSONObject().put("text", text).toString() + "\n"
            connection.outputStream.write(request.toByteArray(Charsets.UTF_8))
            connection.outputStream.flush()
            val response = JSONObject(CompanionEndpoint.readLine(connection.inputStream))
            val copied = response.getBoolean("clipboard_copied")
            val pasted = response.getBoolean("paste_sent")
            return when {
                copied && pasted -> "Текст на ПК · Ctrl+V отправлен"
                copied -> "Текст скопирован на ПК. Нажми Ctrl+V вручную"
                response.optString("error") in setOf("clipboard_busy", "busy") -> "Буфер ПК занят. Попробуй ещё раз"
                response.optString("error") == "invalid_text" -> "ПК отклонил текст"
                else -> "Не удалось записать текст в буфер ПК"
            }
        } finally {
            synchronized(lock) { if (socket === connection) socket = null }
            connection.close()
        }
    }
}
