package belowevolve.airremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Socket
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import org.json.JSONObject

internal class CompanionClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStatus: (Boolean, String?) -> Unit,
    private val onLayout: (String) -> Unit,
    private val onConnecting: (Boolean) -> Unit = {},
    private val onCapabilities: (Set<String>) -> Unit = {},
    private val watchTextFocus: () -> Boolean = { false },
    private val onTextFocus: (Long) -> Unit = {},
) {
    private class Session {
        var job: Job? = null
        @Volatile var socket: Socket? = null
        @Volatile var ready = false
    }

    @Volatile private var session: Session? = null
    private var previous: Session? = null
    private val lock = Any()

    fun start() {
        if (session?.job?.isActive == true) return
        openSession()
    }

    fun refreshTextFocus() {
        // The subscription is negotiated in hello. Keep a working control connection
        // until its replacement is ready, instead of publishing a fake disconnection.
        val active = session
        if (active?.ready == true) {
            cancel(previous)
            previous = active
        } else cancel(active)
        openSession()
    }

    private fun openSession() {
        val endpoint =
            CompanionEndpoint.load(context)
                ?: run {
                    stop()
                    return
                }
        val current = Session()
        val watchFocus = watchTextFocus()
        session = current
        current.job = scope.launch {
            while (isActive) {
                if (previous == null) onCapabilities(emptySet())
                onConnecting(true)
                try {
                    withContext(Dispatchers.IO) {
                        val manager = context.getSystemService(ConnectivityManager::class.java)
                        val network = manager.activeNetwork
                        check(
                            network != null &&
                                manager
                                    .getNetworkCapabilities(network)
                                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                        ) {
                            "Для компаньона нужен Wi-Fi"
                        }
                        val connection =
                            endpoint.open(network, "control", watchFocus) { pending ->
                                synchronized(lock) {
                                    if (session !== current) {
                                        pending.close()
                                        throw CancellationException()
                                    }
                                    current.socket = pending
                                }
                            }
                        try {
                            val input = connection.inputStream.buffered()
                            withContext(Dispatchers.Main) {
                                if (session !== current) throw CancellationException()
                                current.ready = true
                                onConnecting(false)
                                onStatus(true, null)
                            }
                            while (isActive) {
                                val state = JSONObject(CompanionEndpoint.readLine(input))
                                // A non-capability frame confirms the replacement's
                                // server-side control session has entered its event loop.
                                if (!state.has("capabilities"))
                                    withContext(Dispatchers.Main) {
                                        if (session === current) retirePrevious()
                                    }
                                if (state.has("capabilities")) {
                                    val values = state.getJSONArray("capabilities")
                                    val capabilities =
                                        (0 until values.length())
                                            .map { values.getString(it) }
                                            .toSet()
                                    withContext(Dispatchers.Main) {
                                        if (session === current) onCapabilities(capabilities)
                                    }
                                }
                                if (state.has("text_focus") && state.optBoolean("editable"))
                                    withContext(Dispatchers.Main) {
                                        if (session === current)
                                            onTextFocus(state.getLong("text_focus"))
                                    }
                                if (state.has("language"))
                                    withContext(Dispatchers.Main) {
                                        if (session === current)
                                            onLayout(state.getString("language"))
                                    }
                            }
                        } finally {
                            current.ready = false
                            connection.close()
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException || !isActive || session !== current)
                        throw CancellationException()
                    retirePrevious()
                    onConnecting(false)
                    onCapabilities(emptySet())
                    onStatus(false, e.message ?: "Компаньон недоступен")
                }
                delay(3000)
            }
        }
    }

    private fun cancel(old: Session?) {
        if (old == null) return
        old.job?.cancel()
        val socket = synchronized(lock) { old.socket.also { old.socket = null } }
        Dispatchers.IO.dispatch(EmptyCoroutineContext) { runCatching { socket?.close() } }
    }

    private fun retirePrevious() {
        cancel(previous)
        previous = null
    }

    fun stop() {
        val old = synchronized(lock) { session.also { session = null } }
        cancel(old)
        retirePrevious()
        onConnecting(false)
        onCapabilities(emptySet())
        onStatus(false, null)
    }
}
