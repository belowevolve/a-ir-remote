package dev.air.remote

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

internal data class MicStatus(
    val active: Boolean = false,
    val streaming: Boolean = false,
    val muted: Boolean = false,
    val message: String = "Готов к подключению",
    val format: String = "48 кГц · моно · PCM без сжатия",
    val peak: Float = 0f,
    val dropped: Long = 0,
)

/** Owns recording independently of activities. Never restarts recording without a user action. */
class MicrophoneService : Service() {
    companion object {
        private val mutableStatus = MutableStateFlow(MicStatus())
        internal val status = mutableStatus.asStateFlow()
        private const val STOP = "dev.air.remote.MIC_STOP"
        private const val MUTE = "dev.air.remote.MIC_MUTE"
        private const val CHANNEL = "microphone"
        private const val NOTIFICATION = 48

        fun start(context: Context, host: String, port: Int, natural: Boolean) {
            context.startForegroundService(Intent(context, MicrophoneService::class.java)
                .putExtra("host", host).putExtra("port", port).putExtra("natural", natural))
        }
        fun stop(context: Context) { context.stopService(Intent(context, MicrophoneService::class.java)) }
        fun mute(context: Context) {
            context.startService(Intent(context, MicrophoneService::class.java).setAction(MUTE))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: Job? = null
    @Volatile private var destroyed = false
    @Volatile private var streamFailure: String? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var recorder: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == MUTE) {
            if (session != null) {
                mutableStatus.update { it.copy(muted = !it.muted, peak = 0f) }
                notifyStatus()
            } else stopSelf()
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Разреши доступ к микрофону"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Микрофон для ПК", NotificationManager.IMPORTANCE_LOW))
            mutableStatus.value = MicStatus(active = true, message = "Подключение…")
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification())
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "AirRemote:Microphone").apply {
                    setReferenceCounted(false)
                    acquire(10 * 60 * 1000L)
                }
            scope.launch {
                while (isActive) {
                    delay(5 * 60 * 1000L)
                    withContext(Dispatchers.Main) { if (!destroyed) wakeLock?.acquire(10 * 60 * 1000L) }
                }
            }
            val host = intent.getStringExtra("host").orEmpty()
            val port = intent.getIntExtra("port", 54345)
            val natural = intent.getBooleanExtra("natural", true)
            session = scope.launch {
                var failure: String? = null
                try { stream(host, port, natural) }
                catch (_: CancellationException) { }
                catch (e: Exception) { failure = streamFailure ?: e.message ?: "Соединение прервано" }
                finally {
                    closeSocket()
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (!destroyed) {
                            mutableStatus.update { it.copy(active = false, streaming = false, peak = 0f,
                                message = failure ?: "Трансляция остановлена") }
                            stopSelf()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            mutableStatus.value = MicStatus(message = e.message ?: "Не удалось запустить микрофон")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun stream(host: String, port: Int, natural: Boolean) = coroutineScope {
        require(port in 1..65535) { "Порт должен быть от 1 до 65535" }
        // Numeric LAN addresses only: no DNS stalls or accidental Internet streaming.
        require(host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && host.split('.').all { it.toInt() in 0..255 }) {
            "Укажи IPv4-адрес ПК, например 192.168.1.20"
        }
        val address = InetAddress.getByName(host)
        require(address.isSiteLocalAddress || address.isLinkLocalAddress) { "Нужен локальный IP-адрес ПК" }
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Разреши доступ к устройствам поблизости")
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        require(network != null && cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            "Подключи телефон к Wi-Fi в одной сети с ПК (без VPN)"
        }
        val connection = Socket()
        socket = connection
        ensureActive()
        network.bindSocket(connection)
        connection.tcpNoDelay = true
        connection.sendBufferSize = 8192
        connection.soTimeout = 3000
        connection.connect(InetSocketAddress(address, port), 3000)
        MicTransport.handshake(connection.getInputStream(), connection.getOutputStream())
        ensureActive()
        val audioManager = getSystemService(AudioManager::class.java)
        val unprocessed = natural && audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
            else if (natural) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val (record, floatPcm) = createRecorder(source)
        recorder = record
        try {
            ensureActive()
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            }?.let { record.setPreferredDevice(it) }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Микрофон недоступен" }
            mutableStatus.update { it.copy(streaming = true, message = "Передача на $host:$port",
                format = "48 кГц · моно · ${if (floatPcm) "Float32" else "PCM16"} · ${if (unprocessed) "без обработки" else if (natural) "распознавание речи" else "связь"}") }
            notifyStatus()
            val frames = Channel<FloatArray>(capacity = 6)
            val progress = AtomicLong(SystemClock.elapsedRealtime())
            // Socket SO_TIMEOUT only limits reads. Close a blocked writer explicitly.
            val watchdog = scope.launch {
                while (isActive) {
                    delay(100)
                    if (SystemClock.elapsedRealtime() - progress.get() >= 1500) {
                        streamFailure = "Сеть или микрофон не отвечает. Подключись заново"
                        runCatching { connection.close() }
                        runCatching { record.stop() }
                        frames.close(java.io.IOException("Сеть или микрофон не отвечает. Подключись заново"))
                        break
                    }
                }
            }
            val capture = launch(Dispatchers.IO) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val shorts = ShortArray(MicTransport.SAMPLES)
                var ticks = 0
                while (isActive) {
                    val samples = FloatArray(MicTransport.SAMPLES)
                    var offset = 0
                    while (offset < samples.size) {
                        ensureActive()
                        val count = if (floatPcm) record.read(samples, offset, samples.size - offset, AudioRecord.READ_BLOCKING)
                            else record.read(shorts, offset, samples.size - offset, AudioRecord.READ_BLOCKING)
                        check(count > 0) { "Ошибка чтения микрофона: $count" }
                        if (!floatPcm) for (i in offset until offset + count) samples[i] = shorts[i] / 32768f
                        offset += count
                    }
                    // Keep draining the hardware during mute; silence replaces the captured frame.
                    if (mutableStatus.value.muted) samples.fill(0f)
                    if (!frames.trySend(samples).isSuccess) {
                        frames.tryReceive()
                        frames.trySend(samples)
                        mutableStatus.update { it.copy(dropped = it.dropped + 1) }
                    }
                    if (++ticks % 10 == 0) mutableStatus.update { state ->
                        state.copy(peak = if (state.muted) 0f else samples.maxOf { abs(it) }.coerceIn(0f, 1f))
                    }
                }
            }
            try {
                for (samples in frames) {
                    ensureActive()
                    MicTransport.write(connection.getOutputStream(), MicTransport.frame(samples, samples.size, mutableStatus.value.muted, floatPcm))
                    progress.set(SystemClock.elapsedRealtime())
                }
            } finally {
                watchdog.cancel()
                capture.cancel()
                runCatching { record.stop() }
                withContext(NonCancellable) { capture.join() }
                frames.cancel()
            }
        } finally {
            recorder = null
            record.release()
        }
    }

    private fun createRecorder(source: Int): Pair<AudioRecord, Boolean> {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Разреши доступ к микрофону")
        for (encoding in listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
            val min = AudioRecord.getMinBufferSize(MicTransport.RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
            if (min <= 0) continue
            val record = runCatching { AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(MicTransport.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(encoding).build())
                .setBufferSizeInBytes(maxOf(min, MicTransport.SAMPLES * 4 * 4)).build() }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record to (encoding == AudioFormat.ENCODING_PCM_FLOAT)
            record.release()
        }
        error("Телефон не поддерживает запись 48 кГц")
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MicrophoneService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val mute = PendingIntent.getService(this, 2, Intent(this, MicrophoneService::class.java).setAction(MUTE), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_remote_monochrome)
            .setContentTitle("Микрофон для ПК").setContentText(if (status.value.muted) "Микрофон выключен" else status.value.message)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, if (status.value.muted) "Включить звук" else "Без звука", mute).build())
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
    }
    private fun notifyStatus() { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification()) }
    private fun closeSocket() { runCatching { socket?.close() }; socket = null }

    override fun onDestroy() {
        destroyed = true
        closeSocket()
        scope.cancel()
        runCatching { recorder?.stop() }
        wakeLock?.let { if (it.isHeld) it.release() }
        mutableStatus.update { it.copy(active = false, streaming = false, muted = false, peak = 0f,
            message = if (it.active) "Трансляция остановлена" else it.message) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
