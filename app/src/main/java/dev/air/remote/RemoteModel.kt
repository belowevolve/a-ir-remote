package dev.air.remote

import android.app.Application
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    var message by mutableStateOf(value = "")
    val devices = mutableStateListOf<Pair<String, String>>()
    private val prefs = app.getSharedPreferences("remote", Context.MODE_PRIVATE)
    var host by mutableStateOf(value = prefs.getString("host", "")!!)
    var tvName by mutableStateOf(value = prefs.getString("name", "Haier S2 Pro")!!)
    var irPattern by mutableStateOf(value = prefs.getString("ir", "")!!)
    private val ir = app.getSystemService(ConsumerIrManager::class.java)
    val hasIr = ir?.hasIrEmitter() == true
    private val identity by lazy { TvIdentity(app) }
    private val client by lazy { initialized = true; TvClient(identity) }
    private var connectionJob: Job? = null
    private var voiceJob: Job? = null
    private val nsd = app.getSystemService(NsdManager::class.java)
    private var discovery: NsdManager.DiscoveryListener? = null
    private var foreground = false
    private var initialized = false

    fun resume() {
        foreground = true
        discover()
        if (host.isNotBlank()) connect()
    }

    fun pause() {
        foreground = false
        stopVoice()
        connectionJob?.cancel()
        connectionJob = null
        if (initialized) client.close()
        connected = false
        stopDiscovery()
    }

    fun discover() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                discovery = null
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                
                nsd.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val address = info.host?.hostAddress ?: return
                        viewModelScope.launch {
                            if (devices.none { it.second == address }) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Ошибка соединения"
        }
    }

    fun beginPairing() {
        if (busy) return
        busy = true
        connectionJob?.cancel()
        client.close()
        connected = false
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { client.beginPairing(host) }
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
                try {
                    withContext(Dispatchers.IO) {
                        client.listen(host) {
                            viewModelScope.launch {
                                connected = true
                                status = "Подключён по Wi-Fi"
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("AirRemote", "TV disconnected", e)
                    connected = false
                    status = "ТВ недоступен · ИК работает отдельно"
                }
                delay(3.seconds)
            }
        }
    }

    fun key(code: Int) {
        if (connected) {
            action { client.key(code) }
        } else {
            message = "Сначала подключите ТВ"
            connect()
        }
    }

    fun launchYouTube() {
        if (connected) {
            action { client.launchYouTube() }
        } else {
            message = "Сначала подключите ТВ"
        }
    }

    fun saveIr(value: String) {
        try {
            IrSignal.parse(value)
            irPattern = value.trim()
            prefs.edit {
                putString("ir", irPattern)
            }
            message = "ИК-сигнал сохранён"
        } catch (e: Exception) {
            message = e.message ?: "Неверный сигнал"
        }
    }

    fun testHaierPower() {
        action {
            check(hasIr) { "Нет ИК-передатчика" }
            val signal = IrSignal.parse(IrSignal.haierPower())
            ir.transmit(signal.first, signal.second)
        }
    }

    fun power() {
        if (irPattern.isBlank()) {
            message = "Нужно настроить ИК-сигнал питания"
            return
        }
        action {
            check(hasIr) { "В телефоне нет ИК-передатчика" }
            val signal = IrSignal.parse(irPattern)
            ir.transmit(signal.first, signal.second)
        }
        connect()
    }

    fun startVoice() {
        if (voiceJob?.isActive == true) return
        if (!connected) {
            message = "Сначала подключите ТВ"
            return
        }
        recording = true
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
                // 20 KiB gives the TV a stable stream; TvClient also pads a final chunk.
                val buffer = ByteArray(20 * 1024)
                val deadline = System.currentTimeMillis() + 30000
                while ((currentCoroutineContext().isActive) && (System.currentTimeMillis() < deadline)) {
                    val count = input.read(buffer, 0, buffer.size)
                    check(count > 0) { "Ошибка записи микрофона: $count" }
                    client.audio(id, buffer.copyOf(count))
                }
            } finally {
                recorder?.let { runCatching { it.stop() }; it.release() }
                id?.let { runCatching { client.endVoice(it) } }
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

/** Portable format: carrier frequency in Hz followed by alternating on/off durations in µs. */
object IrSignal {
    // NEC address 0x04, command 0x08. Known Haier profile, requires device verification.
    fun haierPower(): String {
        val durations = mutableListOf(9000, 4500)
        for (byte in listOf(0x04, 0xfb, 0x08, 0xf7)) {
            repeat(8) { bit ->
                durations.add(560)
                durations.add(if ((byte and (1 shl bit)) != 0) 1690 else 560)
            }
        }
        durations.add(560)
        return "38000 " + durations.joinToString(" ")
    }

    fun parse(value: String): Pair<Int, IntArray> {
        val numbers = value.trim().split(Regex("[\\s,;]+")).map {
            requireNotNull(it.toIntOrNull()) { "Нужны целые числа: частота и длительности" }
        }
        require(numbers.size >= 4) { "Нужны частота и как минимум три длительности" }
        require(numbers[0] in (20000..60000)) { "Частота должна быть от 20000 до 60000 Гц" }
        val pattern = numbers.drop(1).toIntArray()
        require((pattern.size <= 2000) && (pattern.all { it in (1..100000) })) { "Неверные длительности ИК-сигнала" }
        require(pattern.sumOf { it.toLong() } <= 2000000) { "Сигнал должен быть короче 2 секунд" }
        return numbers[0] to pattern
    }
}
