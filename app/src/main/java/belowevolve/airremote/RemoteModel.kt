package belowevolve.airremote

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.hardware.ConsumerIrManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RemoteModel(app: Application) : AndroidViewModel(app) {
    var status by mutableStateOf(value = "ТВ не выбран")
        private set
    var connected by mutableStateOf(value = false)
        private set
    var connecting by mutableStateOf(false)
        private set
    internal var deviceDialog by mutableStateOf(TvDeviceDialogState.CLOSED)
        private set
    val pairing: Boolean get() = deviceDialog.expectsCode
    val busy: Boolean get() = deviceDialog.busy

    internal fun showDeviceDialog() {
        if (!busy && !pairing)
            deviceDialog = if (connected) TvDeviceDialogState.SETTINGS else TvDeviceDialogState.DEVICES
    }

    internal fun dismissDeviceDialog() {
        if (busy || pairing) cancelPairing() else deviceDialog = TvDeviceDialogState.CLOSED
    }
    // A foreground reconnect is not a request for manual intervention.
    var connectionNeedsAttention by mutableStateOf(false)
        private set
    var recording by mutableStateOf(value = false)
    var voiceRequested by mutableStateOf(false)
        private set
    var message by mutableStateOf(value = "")
    
    var keyboardVisible by mutableStateOf(value = false)
        private set
    var keyboardValue by mutableStateOf(TextFieldValue())
        private set
    var keyboardInputType by mutableIntStateOf(1)
        private set
    var keyboardImeOptions by mutableIntStateOf(0)
        private set

    private var keyboardEpoch = 0L
    private var keyboardRevision = 0L
    @Volatile private var connectionGeneration = 0L
    private var networkPowerHeld = false
    private var networkOkHeld = false

    private data class TvCommand(val generation: Long, val wake: Boolean, val action: TvClient.() -> Unit)
    private val commands = Channel<TvCommand>(Channel.UNLIMITED)

    val devices = mutableStateListOf<Pair<String, String>>()
    private val prefs = app.getSharedPreferences("remote", Context.MODE_PRIVATE)
    var autoKeyboard by mutableStateOf(prefs.getBoolean("auto_keyboard", true))
        private set
    fun updateAutoKeyboard(enabled: Boolean) { autoKeyboard = enabled; prefs.edit { putBoolean("auto_keyboard", enabled) } }
    var host by mutableStateOf(value = prefs.getString("host", "")!!)
        private set
    var tvName by mutableStateOf(value = prefs.getString("name", null)?.takeUnless { it == "Haier S2 Pro" } ?: "Телевизор")
        private set
    var hasPairedTv by mutableStateOf(host.isNotBlank() && app.getSharedPreferences("tv_identity", Context.MODE_PRIVATE).contains(host))
        private set
    private val powerSignal: Pair<Int, IntArray>?
        get() {
            val custom = prefs.getString("ir", null)?.takeIf {
                it.isNotBlank() && prefs.getString("ir_host", null) == host
            }
            return custom?.let { runCatching { IrSignal.parse(it) }.getOrNull() } ?: IrSignal.powerFor(tvName)
        }

    private val ir = app.getSystemService(ConsumerIrManager::class.java)
    val hasIr = ir?.hasIrEmitter() == true
    
    private val identity by lazy { TvIdentity(app) }
    private val client by lazy { initialized = true; TvClient(identity) }
    
    private var connectionJob: Job? = null
    private var pairingJob: Job? = null
    private var pairingCleanupJob: Job? = null
    private var pairingGeneration = 0
    private var voiceJob: Job? = null
    private val nsd = app.getSystemService(NsdManager::class.java)
    private var discovery: NsdManager.DiscoveryListener? = null
    private var foreground = false
    private var tvMode = true
    var onMicrophonePermissionNeeded: (() -> Unit)? = null

    fun setTvMode(enabled: Boolean) {
        tvMode = enabled
        if (!enabled) stopVoice()
    }
    private var initialized = false

    init {
        connectionNeedsAttention = host.isNotBlank() && !prefs.getBoolean("auto_connect", true)
        if (host.isNotBlank() && prefs.contains("ir") && !prefs.contains("ir_host")) {
            prefs.edit { putString("ir_host", host) }
        }
        viewModelScope.launch {
            for (command in commands) {
                if (!connected || (command.generation != connectionGeneration)) continue
                try {
                    withContext(Dispatchers.IO) {
                        if (command.generation == connectionGeneration) {
                            if (command.wake) client.ensureAwake()
                            if (command.generation == connectionGeneration) command.action(client)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (command.generation != connectionGeneration) continue
                    Log.e("AirRemote", "TV command failed", e)
                    if (e is java.io.IOException) client.close()
                    message = e.message ?: "Не удалось отправить команду на ТВ"
                }
            }
        }
    }

    private fun resetConnectionState() {
        connectionGeneration++
        networkPowerHeld = false
        networkOkHeld = false
        voiceRequested = false
        stopVoice()
        keyboardVisible = false
        keyboardValue = TextFieldValue()
        keyboardEpoch = 0L
        keyboardRevision = 0L
    }

    private fun receiveKeyboard(update: KeyboardUpdate) {
        keyboardInputType = update.inputType
        keyboardImeOptions = update.imeOptions
        
        if (update.epoch != keyboardEpoch || update.revision >= keyboardRevision) {
            keyboardEpoch = update.epoch
            keyboardRevision = update.revision
            val text = update.value
            val selection = TextRange(text.start, text.end)
            if (keyboardValue.text != text.text || keyboardValue.selection != selection) {
                keyboardValue = TextFieldValue(text.text, selection)
            }
        }

        if (foreground && update.show && autoKeyboard && tvMode) {
            keyboardVisible = true
        }
    }

    fun showKeyboard() {
        if (!requireConnection()) return
        keyboardVisible = true
    }

    fun hideKeyboard() {
        keyboardVisible = false
    }

    fun editKeyboard(value: TextFieldValue) {
        if (!connected) return
        
        val previous = keyboardValue
        keyboardValue = value
        
        if (previous.text == value.text && previous.selection == value.selection) return
        keyboardRevision++
        
        val epoch = keyboardEpoch
        val revision = keyboardRevision
        val text = KeyboardText(value.text, value.selection.start, value.selection.end)
        enqueue { editKeyboard(epoch, revision, text) }
    }

    fun backspaceKeyboard(count: Int = 1) {
        if (count <= 0) return
        val epoch = keyboardEpoch
        enqueue { backspaceKeyboard(epoch, count) }
    }

    fun submitKeyboard() {
        val epoch = keyboardEpoch
        enqueue { submitKeyboard(epoch) }
        hideKeyboard()
    }

    fun resume() {
        foreground = true
        discover()
        if (host.isNotBlank() && prefs.getBoolean("auto_connect", true)) connect()
    }

    fun pause() {
        foreground = false
        if (pairing || pairingJob?.isActive == true) cancelPairing()
        // Keep a healthy TV session (and its heartbeat reader) across app switches.
        // Only inputs and discovery belong to the visible activity.
        holdOk(false)
        holdPower(false)
        voiceRequested = false
        stopVoice()
        hideKeyboard()
        stopDiscovery()
    }

    fun discover() {
        if (discovery != null) return
        devices.clear()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                val owner = this
                viewModelScope.launch { if (discovery === owner) discovery = null }
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) {
                val owner = this
                viewModelScope.launch {
                    if (discovery === owner) devices.removeAll { it.first == service.serviceName }
                }
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                val owner = this
                @Suppress("DEPRECATION")
                nsd.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val address = info.host?.hostAddress ?: return
                            viewModelScope.launch {
                                if (discovery === owner && devices.none { it.second == address }) {
                                    devices.add(info.serviceName to address)
                                    if (host == address && tvName != info.serviceName) {
                                        tvName = info.serviceName
                                        prefs.edit { putString("name", tvName) }
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
        discovery = listener
        nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    fun select(name: String, address: String) {
        if (busy) return
        disconnect()
        deviceDialog = TvDeviceDialogState.CONNECTING
        host = address.trim()
        tvName = name
        hasPairedTv = false
        prefs.edit {
            putString("host", host)
            putString("name", tvName)
        }
        val target = host
        val generation = connectionGeneration
        viewModelScope.launch {
            try {
                val paired = withContext(Dispatchers.IO) { identity.hasPin(target) }
                if (generation != connectionGeneration || host != target) return@launch
                hasPairedTv = paired
                if (paired) {
                    connect()
                } else beginPairing(target)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == connectionGeneration && host == target) {
                    deviceDialog = TvDeviceDialogState.DEVICES
                    message = e.message ?: "Ошибка соединения"
                }
            }
        }
    }

    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            message = e.message ?: "Ошибка соединения"
        }
    }

    private fun beginPairing(targetHost: String) {
        if (targetHost.isBlank()) {
            deviceDialog = TvDeviceDialogState.DEVICES
            message = "Не удалось определить адрес ТВ"
            return
        }
        deviceDialog = TvDeviceDialogState.CONNECTING
        closeConnection()
        val generation = ++pairingGeneration
        val cleanup = pairingCleanupJob
        pairingJob = viewModelScope.launch {
            try {
                cleanup?.join()
                withContext(Dispatchers.IO) { client.beginPairing(targetHost) }
                if (generation != pairingGeneration) return@launch
                deviceDialog = TvDeviceDialogState.CODE
                status = "Введите код с экрана ТВ"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation != pairingGeneration) return@launch
                Log.e("AirRemote", "Pairing failed", e)
                deviceDialog = TvDeviceDialogState.DEVICES
                status = "Сопряжение не удалось"
                message = e.message ?: "Не удалось начать сопряжение"
            }
        }
    }

    fun finishPairing(pin: String) {
        if (deviceDialog != TvDeviceDialogState.CODE) return
        deviceDialog = TvDeviceDialogState.VERIFYING
        val generation = pairingGeneration
        pairingJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { client.finishPairing(pin.trim()) }
                if (generation != pairingGeneration) return@launch
                hasPairedTv = true
                deviceDialog = TvDeviceDialogState.CLOSED
                connect()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation != pairingGeneration) return@launch
                deviceDialog = TvDeviceDialogState.CODE
                Log.e("AirRemote", "PIN verification failed", e)
                message = e.message ?: "Не удалось подтвердить код"
            }
        }
    }

    fun cancelPairing() {
        closeConnection()
        val previous = pairingJob
        val cleanup = pairingCleanupJob
        pairingGeneration++
        previous?.cancel()
        pairingJob = null
        deviceDialog = TvDeviceDialogState.CLOSED
        prefs.edit { putBoolean("auto_connect", false) }
        connectionNeedsAttention = host.isNotBlank()
        status = "Сопряжение отменено"
        pairingCleanupJob = viewModelScope.launch(Dispatchers.IO) {
            cleanup?.join()
            client.cancelPairing()
            previous?.join()
            client.cancelPairing()
        }
    }

    private fun closeConnection() {
        connectionJob?.cancel()
        connectionJob = null
        resetConnectionState()
        if (initialized) client.close()
        connected = false
        connecting = false
    }

    fun disconnect() {
        prefs.edit { putBoolean("auto_connect", false) }
        closeConnection()
        status = "ТВ отключён"
        connectionNeedsAttention = host.isNotBlank()
    }

    fun forgetTv() {
        val previousHost = host
        disconnect()
        if (previousHost.isNotBlank()) identity.forget(previousHost)
        prefs.edit {
            remove("host")
            remove("name")
            if (prefs.getString("ir_host", null) == previousHost) {
                remove("ir")
                remove("ir_host")
            }
        }
        host = ""
        deviceDialog = TvDeviceDialogState.CLOSED
        hasPairedTv = false
        tvName = "Телевизор"
        message = ""
        status = "ТВ не выбран"
    }

    fun connect(wake: Boolean = false) {
        if (host.isBlank()) return
        prefs.edit { putBoolean("auto_connect", true) }
        if (wake && foreground) closeConnection()
        if (!foreground || (connectionJob?.isActive == true)) return
        connecting = true
        connectionJob = viewModelScope.launch {
            val owner = currentCoroutineContext()[Job]
            try {
                var wakePending = wake
                val wakeDeadline = android.os.SystemClock.elapsedRealtime() + 30000
                val paired = withContext(Dispatchers.IO) { identity.hasPin(host) }
                if (!paired) {
                    connecting = false
                    hasPairedTv = false
                    status = "Нужно сопряжение"
                    connectionNeedsAttention = true
                    return@launch
                }
                var reconnectAttempts = 0
                // A retry delay may finish after the activity has gone to the background.
                // Keep an existing listen() alive, but only start new sessions while visible.
                while (isActive && foreground) {
                    status = "Подключение…"
                    val generation = connectionGeneration
                    try {
                        withContext(Dispatchers.IO) {
                            client.listen(host, onKeyboard = { update ->
                                viewModelScope.launch {
                                    if (generation == connectionGeneration) receiveKeyboard(update)
                                }
                            }, onVoiceRequest = { requested ->
                                viewModelScope.launch {
                                    if (generation == connectionGeneration) {
                                        voiceRequested = foreground && requested
                                        if (!voiceRequested) stopVoice()
                                        else if (tvMode) {
                                            keyboardVisible = false
                                            // A new TV session replaces any previous recorder. Wait
                                            // for its cleanup before opening the microphone again.
                                            stopVoice()
                                            voiceJob?.join()
                                            if (foreground && tvMode && generation == connectionGeneration && voiceRequested) {
                                                if (getApplication<Application>().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
                                                else onMicrophonePermissionNeeded?.invoke()
                                            }
                                        }
                                    }
                                }
                            }) {
                                viewModelScope.launch {
                                    if (generation == connectionGeneration) {
                                        connected = true
                                        connecting = false
                                        if (deviceDialog == TvDeviceDialogState.CONNECTING)
                                            deviceDialog = TvDeviceDialogState.CLOSED
                                        connectionNeedsAttention = false
                                        reconnectAttempts = 0
                                        status = "Подключён по Wi-Fi"
                                        if (wakePending) {
                                            wakePending = false
                                            // Recheck power when this queued command executes: a
                                            // physical remote may have already woken the screen.
                                            enqueue {}
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // Closing an old socket can surface as IOException after a new
                        // connection has started. Its result must not reset the new session.
                        if (!currentCoroutineContext().isActive || generation != connectionGeneration) return@launch
                        Log.w("AirRemote", "TV disconnected", e)
                        connected = false
                        connecting = true
                        connectionNeedsAttention = true
                        resetConnectionState()
                        status = "ТВ недоступен"
                        // A lost background session is retried when the app returns.
                        if (!foreground) break
                        if (wakePending && android.os.SystemClock.elapsedRealtime() >= wakeDeadline) {
                            connecting = false
                            message = "ТВ не отвечает. Сетевой режим ожидания может быть отключён"
                            break
                        }
                    }
                    val retryDelay = when (reconnectAttempts++) {
                        0 -> 250.milliseconds
                        1 -> 1.seconds
                        else -> 3.seconds
                    }
                    delay(retryDelay)
                }
            } finally {
                if (connectionJob === owner) connecting = false
            }
        }
    }

    // All user commands share one FIFO, including keyboard edits and held-key edges.
    private fun enqueue(wake: Boolean = true, action: TvClient.() -> Unit) {
        if (connected) commands.trySend(TvCommand(connectionGeneration, wake, action))
    }

    // Buttons must not turn automatic reconnection into a manual device picker.
    internal fun requireConnection(): Boolean {
        if (connected) return true
        if (!connecting) showDeviceDialog()
        return false
    }

    fun holdOk(pressed: Boolean) {
        if (!pressed) {
            if (networkOkHeld) {
                networkOkHeld = false
                enqueue(wake = false) { sendKey(23, remote.Remotemessage.RemoteDirection.END_LONG) }
            }
            return
        }
        if (!requireConnection() || networkOkHeld) return
        networkOkHeld = true
        enqueue(wake = false) { sendKey(23, remote.Remotemessage.RemoteDirection.START_LONG) }
    }

    // User actions share the same disconnected-state behavior; the transport only sends packets.
    private fun sendCommand(wake: Boolean = true, action: TvClient.() -> Unit) {
        if (!requireConnection()) return
        enqueue(wake, action)
    }

    // Android TV applies its native wake policy to ordinary key events.
    fun key(code: Int) = sendCommand(wake = false) { sendKey(code) }

    fun launchYouTube() = sendCommand { launchAppLink("https://www.youtube.com/tv") }

    fun holdPower(pressed: Boolean) {
        if (!pressed) {
            if (networkPowerHeld) {
                networkPowerHeld = false
                enqueue(wake = false) { sendKey(26, remote.Remotemessage.RemoteDirection.END_LONG) }
            }
            return
        }
        if (!connected) {
            // IR supports a short power toggle only. Use it to wake an offline
            // TV; a connected TV must receive real network hold/release edges.
            if (hasIr && powerSignal != null) powerIr() else requireConnection()
            return
        }
        networkPowerHeld = true
        enqueue(wake = false) { sendKey(26, remote.Remotemessage.RemoteDirection.START_LONG) }
    }

    fun power() {
        if (hasIr && powerSignal != null) { powerIr(); return }
        if (connected) { key(26); return }
        requireConnection()
    }

    fun powerIr() {
        if (!hasIr) return
        val signal = powerSignal ?: return
        val pending = if (connected && initialized) client.prepareExternalWake() else null
        action {
            try { ir.transmit(signal.first, signal.second) }
            catch (error: Exception) {
                if (pending != null) client.externalWakeFailed(pending, error)
                throw error
            }
        }
    }

    fun startVoice() {
        if (!foreground || !tvMode || voiceJob?.isActive == true) return
        if (MicrophoneService.status.value.active) {
            message = "Сначала останови микрофон для ПК"
            return
        }
        if (!requireConnection()) return
        if (!voiceRequested) {
            key(84) // SEARCH opens TV voice search; its request starts capture below.
            return
        }
        recording = true
        voiceRequested = false
        voiceJob = action {
            var recorder: AudioRecord? = null
            var id: Int? = null
            try {
                id = client.startVoice()
                val minimum = AudioRecord.getMinBufferSize(
                    8000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                check(minimum > 0) { "Микрофон не поддерживает формат записи" }
                @Suppress("MissingPermission")
                val input = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    8000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, 8192),
                )
                recorder = input
                check(input.state == AudioRecord.STATE_INITIALIZED) { "Микрофон не готов" }
                input.startRecording()
                // Read 20 ms of PCM per network packet. Reading the full recorder
                // buffer (8192 bytes) held back 512 ms at 8 kHz / 16-bit mono.
                val buffer = ByteArray(8000 * 2 / 50)
                val deadline = android.os.SystemClock.elapsedRealtime() + 30000
                while (currentCoroutineContext().isActive && client.isVoiceActive(id) && android.os.SystemClock.elapsedRealtime() < deadline) {
                    val count = input.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    check(count > 0) { "Ошибка записи микрофона: $count" }
                    client.audio(id, buffer, count)
                }
            } finally {
                recorder?.let { runCatching { it.stop() }; it.release() }
                id?.let { runCatching { client.endVoice(it) } }
                // Do not overwrite a newer request while the previous session exits.
                withContext(NonCancellable + Dispatchers.Main) { recording = false }
            }
        }
    }

    fun stopVoice() {
        voiceJob?.cancel()
    }

    override fun onCleared() {
        pause()
        closeConnection()
        commands.close()
        if (initialized) client.cancelPairing()
    }
}
