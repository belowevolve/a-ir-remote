package belowevolve.airremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.Socket

internal class CompanionClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStatus: (Boolean, String?) -> Unit,
    private val onLayout: (String) -> Unit,
) {
    private var job: Job? = null
    @Volatile private var socket: Socket? = null
    private val lock = Any()
    @Volatile private var generation = 0
    fun start() {
        if (job?.isActive == true) return
        val endpoint = CompanionEndpoint.load(context) ?: return
        val current = ++generation
        job = scope.launch {
            while (isActive) {
                onStatus(false, null)
                try {
                    withContext(Dispatchers.IO) {
                        val manager = context.getSystemService(ConnectivityManager::class.java)
                        val network = manager.activeNetwork
                        check(network != null && manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) { "Для компаньона нужен Wi-Fi" }
                        val connection = endpoint.open(network, "control") { pending ->
                            synchronized(lock) {
                                if (generation != current) { pending.close(); throw CancellationException() }
                                socket = pending
                            }
                        }
                        try {
                            withContext(Dispatchers.Main) { if (generation == current) onStatus(true, null) }
                            while (isActive) {
                                val state = JSONObject(CompanionEndpoint.readLine(connection.inputStream))
                                if (state.has("language")) withContext(Dispatchers.Main) {
                                    if (generation == current) onLayout(state.getString("language"))
                                }
                            }
                        } finally { connection.close() }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException || !isActive || generation != current) throw CancellationException()
                    onStatus(false, e.message ?: "Компаньон недоступен")
                }
                delay(3000)
            }
        }
    }
    fun stop() {
        val old = synchronized(lock) {
            generation++
            socket.also { socket = null }
        }
        job?.cancel(); job = null
        CoroutineScope(Dispatchers.IO).launch { runCatching { old?.close() } }
        onStatus(false, null)
    }
}
