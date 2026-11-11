package belowevolve.airremote

import java.net.SocketTimeoutException

/** Re-read power before retrying: a missing ACK must never cause a blind second POWER toggle. */
internal suspend fun ensureVoiceAwake(
    attemptWake: suspend (ackTimeoutMillis: Long) -> Unit,
    refreshScreenOn: suspend () -> Boolean,
    retryWake: Boolean = true,
) {
    try {
        attemptWake(1_500)
    } catch (error: SocketTimeoutException) {
        if (!refreshScreenOn()) {
            if (!retryWake) throw error
            attemptWake(5_000)
        }
    }
}
