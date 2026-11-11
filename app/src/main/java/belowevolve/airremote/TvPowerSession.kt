package belowevolve.airremote

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import remote.Remotemessage.RemoteDirection

/** Screen power is independent of the authenticated remote connection. */
internal class TvPowerSession(
    private val timeoutMillis: Long = 5000,
    private val standbyTransitionMillis: Long = 2500,
) {
    private val lock = Any()
    private var screenOn: Boolean? = null
    private class Wake(val ready: CompletableDeferred<Unit> = CompletableDeferred(), var sent: Boolean = false)
    private var awakening: Wake? = null
    private var enteredStandbyAt: Long? = null
    private fun nowMillis() = System.nanoTime() / 1_000_000

    fun update(on: Boolean) = synchronized(lock) {
        if (!on && screenOn == true) enteredStandbyAt = nowMillis()
        screenOn = on
        if (on) {
            enteredStandbyAt = null
            awakening?.ready?.complete(Unit)
            awakening = null
        }
    }

    fun reset() = synchronized(lock) {
        screenOn = null
        enteredStandbyAt = null
        awakening?.ready?.completeExceptionally(IOException("Соединение с ТВ изменилось"))
        awakening = null
    }

    // A separate power press (including IR) may already be waking the screen.
    fun explicitWakeSent(): CompletableDeferred<Unit>? = synchronized(lock) {
        if (screenOn != false) null
        else (awakening ?: Wake().also { awakening = it }).also { it.sent = true }.ready
    }

    fun keySent(code: Int, direction: RemoteDirection) {
        // A held power key can wake the TV on key-down, before its screen-on ACK.
        // Releasing a key alone must not make later commands wait for a nonexistent wake.
        if ((code == 26 || code == 177) &&
            (direction == RemoteDirection.SHORT || direction == RemoteDirection.START_LONG)) explicitWakeSent()
    }

    fun failed(request: CompletableDeferred<Unit>?, error: Exception) = synchronized(lock) {
        if (request != null && awakening?.ready === request) {
            request.completeExceptionally(error)
            awakening = null
        }
    }

    suspend fun ensureAwake(ackTimeoutMillis: Long = timeoutMillis, sendWake: () -> Unit) {
        val (request, owner) = synchronized(lock) {
            if (screenOn != false) return
            val previous = awakening
            val next = previous ?: Wake().also { awakening = it }
            next to (previous == null)
        }
        try {
            if (owner) {
                // Only a recently observed on→off transition needs time to settle.
                val remaining = synchronized(lock) {
                    enteredStandbyAt?.let { standbyTransitionMillis - (nowMillis() - it) } ?: 0
                }
                // A physical remote or another client can finish waking the TV
                // while it settles. Its screen-on confirmation ends this wait.
                if (remaining > 0) withTimeoutOrNull(remaining) { request.ready.await() }
                val send = synchronized(lock) {
                    (awakening === request && screenOn == false && !request.sent)
                        .also { if (it) request.sent = true }
                }
                if (send) sendWake()
            }
            // An internal timeout is an I/O failure; cancellation by the caller stays cancellation.
            if (withTimeoutOrNull(ackTimeoutMillis) { request.ready.await() } == null)
                throw SocketTimeoutException("ТВ не подтвердил включение экрана. Попробуй ещё раз")
        } catch (error: Exception) {
            // Cancelling a follower must not fail the owner's shared wake.
            if (owner || error !is CancellationException)
                failed(request.ready, if (error is CancellationException) IOException("Ожидание пробуждения отменено", error) else error)
            throw error
        }
    }
}
