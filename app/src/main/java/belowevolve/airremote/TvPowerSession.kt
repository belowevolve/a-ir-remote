package belowevolve.airremote

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

/** Screen power is independent of the authenticated remote connection. */
internal class TvPowerSession(
    private val timeoutMillis: Long = 5000,
    private val standbyTransitionMillis: Long = 2500,
) {
    private val lock = Any()
    private var screenOn: Boolean? = null
    private data class Wake(val ready: CompletableDeferred<Unit> = CompletableDeferred(), var sent: Boolean = false)
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
        awakening?.ready?.completeExceptionally(IOException("\u0421\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u0441 \u0422\u0412 \u0438\u0437\u043c\u0435\u043d\u0438\u043b\u043e\u0441\u044c"))
        awakening = null
    }

    // A separate power press (including IR) may already be waking the screen.
    fun explicitWakeSent(): CompletableDeferred<Unit>? = synchronized(lock) {
        if (screenOn != false) null
        else (awakening ?: Wake().also { awakening = it }).also { it.sent = true }.ready
    }

    fun failed(request: CompletableDeferred<Unit>?, error: Exception) = synchronized(lock) {
        if (request != null && awakening?.ready === request) {
            request.completeExceptionally(error)
            awakening = null
        }
    }

    suspend fun ensureAwake(sendWake: () -> Unit) {
        val (request, owner) = synchronized(lock) {
            if (screenOn != false && awakening == null) return
            val previous = awakening
            val next = previous ?: Wake().also { awakening = it }
            next to (previous == null)
        }
        if (owner) {
            // Some TV power controllers ignore a new POWER while still entering standby.
            // Initial off-state snapshots have no transition time, so normal wake stays immediate.
            val remaining = synchronized(lock) {
                enteredStandbyAt?.let { standbyTransitionMillis - (nowMillis() - it) } ?: 0
            }
            if (remaining > 0) delay(remaining)
            val send = synchronized(lock) {
                if (awakening !== request || screenOn != false || request.sent) false
                else { request.sent = true; true }
            }
            if (send) try {
                sendWake()
            } catch (error: Exception) {
                failed(request.ready, error)
                throw error
            }
        }
        try {
            withTimeout(timeoutMillis) { request.ready.await() }
        } catch (timeout: TimeoutCancellationException) {
            // A timeout must fail this command, not cancel the FIFO consumer permanently.
            throw SocketTimeoutException("\u0422\u0412 \u043d\u0435 \u043f\u043e\u0434\u0442\u0432\u0435\u0440\u0434\u0438\u043b \u0432\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u0435 \u044d\u043a\u0440\u0430\u043d\u0430. \u041f\u043e\u043f\u0440\u043e\u0431\u0443\u0439 \u0435\u0449\u0451 \u0440\u0430\u0437")
        }
    }

    companion object {
        fun shouldWakeBeforeKey(code: Int): Boolean = when (code) {
            26, 177, 223, 224, 276 -> false // Power toggles and explicit sleep/wake commands.
            else -> true
        }
    }
}
