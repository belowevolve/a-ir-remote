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
    var status by mutableStateOf(value = "Выберите телевизор")
    var connected by mutableStateOf(value = false)
    var pairing by mutableStateOf(value = false)
    var busy by mutableStateOf(value = false)
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
    private var connectionGeneration = 0L

    private data class TvCommand(val generation: Long, val action: TvClient.() -> Unit)
    private val commands = Channel<TvCommand>(Channel.UNLIMITED)

    val devices = mutableStateListOf<Pair<String, String>>()
    private val prefs = app.getSharedPreferences("remote", Context.MODE_PRIVATE)
    var host by mutableStateOf(value = prefs.getString("host", "")!!)
    var tvName by mutableStateOf(value = prefs.getString("name", "Haier S2 Pro")!!)
    private val powerSignal by lazy {
        IrSignal.parse(prefs.getString("ir", null)?.takeIf { it.isNotBlank() } ?: IrSignal.haierPower())
    }
    
    private val ir = app.getSystemService(ConsumerIrManager::class.java)
    val hasIr = ir?.hasIrEmitter() == true
    
    private val identity by lazy { TvIdentity(app) }
    private val client by lazy { initialized = true; TvClient(identity) }
    
    private var connectionJob: Job? = null
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
        viewModelScope.launch {
            for (command in commands) {
                if (!connected || (command.generation != connectionGeneration)) continue
                try {
                    withContext(Dispatchers.IO) {
                        command.action(client)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("AirRemote", "TV command failed", e)
                    if (e is java.io.IOException) client.close()
                    message = e.message ?: "Не удалось отправить команду на ТВ"
                }
            }
        }
    }

    private fun resetConnectionState() {
        connectionGeneration++
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

        if (update.show) {
            keyboardVisible = true
        }
    }

    fun showKeyboard() {
        if (!connected) {
            message = "Сначала подключите ТВ"
            connect()
            return
        }
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
        if (host.isNotBlank()) connect()
    }

    fun pause() {
        foreground = false
        resetConnectionState()
        connectionJob?.cancel()
        connectionJob = null
        if (initialized) client.close()
        connected = false
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
        host = address.trim()
        tvName = name
        prefs.edit {
            putString("host", host)
            putString("name", tvName)
        }
        beginPairing()
    }

    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            message = e.message ?: "Ошибка соединения"
        }
    }

    fun beginPairing(targetHost: String = host) {
        if (targetHost.isBlank()) {
            message = "Введите IP-адрес ТВ"
            return
        }
        if (busy) return
        busy = true
        connectionJob?.cancel()
        client.close()
        connected = false
        resetConnectionState()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { client.beginPairing(targetHost) }
                pairing = true
                status = "Введите код с экрана ТВ"
            } catch (e: Exception) {
                Log.e("AirRemote", "Pairing failed", e)
                status = "Сопряжение не удалось"
                message = e.message ?: "Не удалось начать сопряжение"
            } finally {
                busy = false
            }
        }
    }

    fun finishPairing(pin: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { client.finishPairing(pin.trim()) }
                pairing = false
                connect()
            } catch (e: Exception) {
                Log.e("AirRemote", "PIN verification failed", e)
                message = e.message ?: "Не удалось подтвердить код"
            } finally {
                busy = false
            }
        }
    }

    fun cancelPairing() {
        pairing = false
        action { client.cancelPairing() }
    }

    fun connect() {
        if (!foreground || (connectionJob?.isActive == true)) return
        connectionJob = viewModelScope.launch {
            val paired = withContext(Dispatchers.IO) { identity.hasPin(host) }
            if (!paired) {
                status = "Нужно сопряжение"
                return@launch
            }
            while (isActive) {
                status = "Подключение…"
                val generation = connectionGeneration
                try {
                    withContext(Dispatchers.IO) {
                        client.listen(host, onKeyboard = { update ->
                            viewModelScope.launch {
                                if (foreground && generation == connectionGeneration) receiveKeyboard(update)
                            }
                        }, onVoiceRequest = { requested ->
                            viewModelScope.launch {
                                if (foreground && generation == connectionGeneration) {
                                    voiceRequested = requested
                                    if (!requested) stopVoice()
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
                                if (foreground && generation == connectionGeneration) {
                                    connected = true
                                    status = "Подключён по Wi-Fi"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("AirRemote", "TV disconnected", e)
                    connected = false
                    resetConnectionState()
                    status = "ТВ недоступен · ИК работает отдельно"
                }
                delay(3.seconds)
            }
        }
    }

    // All user commands share one FIFO, including keyboard edits and held-key edges.
    private fun enqueue(action: TvClient.() -> Unit) {
        if (connected) commands.trySend(TvCommand(connectionGeneration, action))
    }

    fun holdOk(pressed: Boolean) {
        enqueue { key(23, if (pressed) remote.Remotemessage.RemoteDirection.START_LONG else remote.Remotemessage.RemoteDirection.END_LONG) }
    }

    fun key(code: Int) {
        if (connected) {
            enqueue { key(code) }
        } else {
            message = "Сначала подключите ТВ"
            connect()
        }
    }

    fun launchYouTube() {
        if (connected) {
            enqueue { launchYouTube() }
        } else {
            message = "Сначала подключите ТВ"
        }
    }

    fun holdPower(pressed: Boolean) {
        enqueue { key(26, if (pressed) remote.Remotemessage.RemoteDirection.START_LONG else remote.Remotemessage.RemoteDirection.END_LONG) }
    }

    fun power() {
        if (!hasIr) { key(26); return }
        action {
            val signal = powerSignal
            ir.transmit(signal.first, signal.second)
        }
        connect()
    }

    fun startVoice() {
        if (!foreground || !tvMode || voiceJob?.isActive == true) return
        if (MicrophoneService.status.value.active) {
            message = "Сначала останови микрофон для ПК"
            return
        }
        if (!connected) {
            message = "Сначала подключите ТВ"
            return
        }
        if (!voiceRequested) {
            message = "Открой голосовой поиск в приложении на ТВ — микрофон включится автоматически"
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
                val buffer = ByteArray(8 * 1024)
                val deadline = android.os.SystemClock.elapsedRealtime() + 30000
                while (currentCoroutineContext().isActive && client.isVoiceActive(id) && android.os.SystemClock.elapsedRealtime() < deadline) {
                    val count = input.read(buffer, 0, buffer.size)
                    check(count > 0) { "Ошибка записи микрофона: $count" }
                    client.audio(id, buffer.copyOf(count))
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
        if (initialized) client.cancelPairing()
    }
}
