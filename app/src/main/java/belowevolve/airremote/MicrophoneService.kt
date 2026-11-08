package belowevolve.airremote

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

internal data class MicStatus(
    val active: Boolean = false,
    val streaming: Boolean = false,
    val muted: Boolean = false,
    val failed: Boolean = false,
    val message: String = "Готов к подключению",
    val format: String = "48 кГц · моно · PCM без сжатия",
    val peak: Float = 0f,
    val dropped: Long = 0,
    val sentFrames: Long = 0,
    val waveform: List<Float> = emptyList(),
)

/** Owns recording independently of activities. Never restarts recording without a user action. */
class MicrophoneService : Service() {
    companion object {
        @Volatile private var waveformEnabled = false
        @Volatile private var iconMeterEnabled = true
        fun setWaveform(context: Context, enabled: Boolean) {
            context.getSharedPreferences("microphone", Context.MODE_PRIVATE).edit().putBoolean("waveform", enabled).apply()
            waveformEnabled = enabled
            if (!enabled) mutableStatus.update { it.copy(peak = if (iconMeterEnabled) it.peak else 0f, waveform = emptyList()) }
        }
        fun setIconMeter(context: Context, enabled: Boolean) {
            context.getSharedPreferences("microphone", Context.MODE_PRIVATE).edit().putBoolean("icon_meter", enabled).apply()
            iconMeterEnabled = enabled
            if (!enabled && !waveformEnabled) mutableStatus.update { it.copy(peak = 0f) }
        }
        private val mutableStatus = MutableStateFlow(MicStatus())
        internal val status = mutableStatus.asStateFlow()
        private const val LOG_TAG = "AirRemoteMic"
        private const val STOP = BuildConfig.APPLICATION_ID + ".MIC_STOP"
        private const val MUTE = BuildConfig.APPLICATION_ID + ".MIC_MUTE"
        private const val CHANNEL = "microphone"
        private const val NOTIFICATION = 48

        fun start(context: Context, natural: Boolean) {
            context.startForegroundService(
                Intent(context, MicrophoneService::class.java)
                    .putExtra("natural", natural),
            )
        }
        fun stop(context: Context) { context.stopService(Intent(context, MicrophoneService::class.java)) }
        fun mute(context: Context) {
            context.startService(Intent(context, MicrophoneService::class.java).setAction(MUTE))
        }
    }

    private data class CapturedFrame(val samples: FloatArray, val capturedAt: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: Job? = null
    @Volatile private var destroyed = false
    @Volatile private var streamFailure: String? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var recorder: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        waveformEnabled = getSharedPreferences("microphone", Context.MODE_PRIVATE).getBoolean("waveform", false)
        iconMeterEnabled = getSharedPreferences("microphone", Context.MODE_PRIVATE).getBoolean("icon_meter", true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == MUTE) {
            if (session != null) {
                // Keep the timeline: muted frames append silence and move old peaks out naturally.
                mutableStatus.update { it.copy(muted = !it.muted, peak = 0f) }
                notifyStatus()
            } else stopSelf()
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Нет доступа к микрофону"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Микрофон для ПК", NotificationManager.IMPORTANCE_LOW))
            mutableStatus.value = MicStatus(active = true, message = "Подключение…")
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification())
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "AirRemote:Microphone").apply {
                    setReferenceCounted(false)
                    acquire(10.minutes.inWholeMilliseconds)
                }
            scope.launch {
                while (isActive) {
                    delay(5.minutes)
                    withContext(Dispatchers.Main) { if (!destroyed) wakeLock?.acquire(10 * 60 * 1000L) }
                }
            }
            val endpoint = CompanionEndpoint.load(this) ?: error("ПК не подключён по Wi-Fi")
            val natural = intent.getBooleanExtra("natural", true)
            session = scope.launch {
                var failure: String? = null
                try { stream(endpoint, natural) }
                catch (_: CancellationException) { }
                catch (e: Exception) { failure = streamFailure ?: e.message ?: "Соединение прервано" }
                finally {
                    closeSocket()
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (!destroyed) {
                            mutableStatus.update { it.copy(active = false, streaming = false,
                                peak = 0f, failed = failure != null,
                                message = failure ?: "Трансляция остановлена") }
                            stopSelf()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            mutableStatus.value = MicStatus(failed = true, message = e.message ?: "Не удалось запустить микрофон")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun stream(endpoint: CompanionEndpoint, natural: Boolean) = coroutineScope {
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Нет доступа к устройствам поблизости")
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        require(network != null && cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            "Нет подключения к локальной сети Wi-Fi"
        }
        val connection = endpoint.open(network, "audio") { pending ->
            socket = pending
            if (destroyed) { pending.close(); throw CancellationException() }
        }
        ensureActive()
        MicTransport.handshake(connection.getInputStream(), connection.getOutputStream())
        ensureActive()
        val audioManager = getSystemService(AudioManager::class.java)
        val unprocessed = natural && audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
            else if (natural) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val (record, floatPcm) = createRecorder(source)
        recorder = record
        val suppressor = if (!natural && NoiseSuppressor.isAvailable()) runCatching {
            NoiseSuppressor.create(record.audioSessionId)
        }.getOrNull() else null
        suppressor?.let { runCatching { it.enabled = true } }
        try {
            ensureActive()
            val encoder = MicTransport.Encoder(floatPcm)
            val output = connection.getOutputStream()
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            }?.let { record.setPreferredDevice(it) }
            val diagnostics = BuildConfig.DEBUG || Log.isLoggable(LOG_TAG, Log.DEBUG)
            // Exactly enough buffers for the bounded queue, the reader and the writer.
            val pool = ArrayBlockingQueue<FloatArray>(MicTransport.QUEUED_FRAMES + 2).apply {
                repeat(MicTransport.QUEUED_FRAMES + 2) { add(FloatArray(MicTransport.SAMPLES)) }
            }
            val frames = Channel<CapturedFrame>(capacity = MicTransport.QUEUED_FRAMES,
                onUndeliveredElement = { pool.offer(it.samples) })
            if (diagnostics) Log.d(LOG_TAG, "captureBufferMs=${record.bufferSizeInFrames * 1000 / MicTransport.RATE} sendBufferBytes=${connection.sendBufferSize}")
            val started = SystemClock.elapsedRealtime()
            val progress = AtomicLong(started)
            // Socket SO_TIMEOUT only limits reads. Close a blocked writer explicitly.
            val watchdog = scope.launch {
                while (isActive) {
                    delay(100.milliseconds)
                    if (SystemClock.elapsedRealtime() - progress.get() >=
                        (if (progress.get() == started) 1500 else 500)) {
                        streamFailure = "Сеть или микрофон не отвечает"
                        runCatching { connection.close() }
                        runCatching { record.stop() }
                        frames.close(java.io.IOException(streamFailure))
                        break
                    }
                }
            }
            val capture = launch(Dispatchers.IO) {
                val previousPriority = Process.getThreadPriority(Process.myTid())
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val shorts = ShortArray(MicTransport.SAMPLES)
                    var ticks = 0
                    var peak = 0f
                    var framesRead = 0L
                    val timestamp = if (diagnostics) AudioTimestamp() else null
                    mutableStatus.update { it.copy(message = "Начало передачи…",
                        format = "48 кГц · моно · ${if (floatPcm) "Float32" else "PCM16"} · ${if (unprocessed) "без обработки" else if (natural) "распознавание речи" else "связь"}") }
                    // Prepare UI state before capture begins, so startup work cannot build
                    // an initial hardware backlog. Notification IPC stays off this thread.
                    launch(Dispatchers.Main) { notifyStatus() }
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Микрофон недоступен" }
                    while (isActive) {
                        val samples = checkNotNull(pool.poll()) { "Буфер микрофона недоступен" }
                        var offset = 0
                        while (offset < samples.size) {
                            ensureActive()
                            val count = if (floatPcm) record.read(samples, offset, samples.size - offset, AudioRecord.READ_BLOCKING)
                                else record.read(shorts, offset, samples.size - offset, AudioRecord.READ_BLOCKING)
                            // stop() unblocks read() with 0; preserve cancellation and the transport error.
                            ensureActive()
                            check(count > 0) { "Ошибка чтения микрофона: $count" }
                            if (!floatPcm) for (i in offset until offset + count) samples[i] = shorts[i] / 32768f
                            offset += count
                        }
                        framesRead += samples.size
                        if (timestamp != null && ticks % 100 == 99 &&
                            record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                            // Estimate the age of the last sample using the capture clock, not read completion.
                            val lastSampleNs = timestamp.nanoTime +
                                (framesRead - timestamp.framePosition) * 1_000_000_000L / MicTransport.RATE
                            Log.d(LOG_TAG, "captureAgeMs=${(System.nanoTime() - lastSampleNs) / 1_000_000.0}")
                        }
                        // Keep draining the hardware during mute; silence replaces the captured frame.
                        if (mutableStatus.value.muted) samples.fill(0f)
                        val measure = waveformEnabled || iconMeterEnabled
                        if (measure) peak = maxOf(peak, samples.maxOf { abs(it) }.coerceIn(0f, 1f)) else peak = 0f
                        val frame = CapturedFrame(samples, System.nanoTime())
                        if (!frames.trySend(frame).isSuccess) {
                            var discarded = 0
                            frames.tryReceive().getOrNull()?.let { pool.offer(it.samples); discarded++ }
                            if (!frames.trySend(frame).isSuccess) { pool.offer(samples); discarded++ }
                            // The writer can drain the queue after trySend fails. A retry
                            // that evicts nothing and succeeds has not lost an audio frame.
                            if (discarded > 0) mutableStatus.update { it.copy(dropped = it.dropped + discarded) }
                        }
                        if (++ticks % 5 == 0 && measure) {
                            val level = peak
                            mutableStatus.update { state ->
                                state.copy(peak = if (state.muted) 0f else level,
                                    waveform = if (waveformEnabled) (state.waveform + if (state.muted) 0f else level).takeLast(64) else emptyList())
                            }
                            peak = 0f
                        }
                    }
                } finally {
                    // This blocking capture loop runs on a shared IO worker.
                    Process.setThreadPriority(previousPriority)
                }
            }
            try {
                var framesWritten = 0
                for (frame in frames) {
                    try {
                        ensureActive()
                        val writeStarted = System.nanoTime()
                        if (!MicTransport.isFresh(frame.capturedAt, writeStarted)) {
                            mutableStatus.update { it.copy(dropped = it.dropped + 1) }
                            continue
                        }
                        output.write(encoder.encode(frame.samples, mutableStatus.value.muted))
                        progress.set(SystemClock.elapsedRealtime())
                        framesWritten++
                        if (framesWritten == 1 || framesWritten % 10 == 0) {
                            mutableStatus.update { it.copy(streaming = true, sentFrames = framesWritten.toLong(),
                                message = "Передача на ${endpoint.host}") }
                            if (framesWritten == 1) launch(Dispatchers.Main) { notifyStatus() }
                        }
                        val writeMs = (System.nanoTime() - writeStarted) / 1_000_000
                        if (diagnostics && (framesWritten % 100 == 0 || writeMs >= 100)) {
                            val queueMs = (writeStarted - frame.capturedAt) / 1_000_000.0
                            Log.d(LOG_TAG, "queueMs=$queueMs writeMs=$writeMs dropped=${mutableStatus.value.dropped}")
                        }
                    } finally {
                        pool.offer(frame.samples)
                    }
                }
            } finally {
                watchdog.cancel()
                capture.cancel()
                runCatching { record.stop() }
                withContext(NonCancellable) { capture.join() }
                frames.cancel()
            }
        } finally {
            suppressor?.release()
            recorder = null
            record.release()
        }
    }

    private fun createRecorder(source: Int): Pair<AudioRecord, Boolean> {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Нет доступа к микрофону")
        for (encoding in listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
            val min = AudioRecord.getMinBufferSize(MicTransport.RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
            if (min <= 0) continue
            val record = runCatching { AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(MicTransport.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(encoding).build())
                .setBufferSizeInBytes(MicTransport.recorderBufferBytes(min, encoding == AudioFormat.ENCODING_PCM_FLOAT)).build() }.getOrNull() ?: continue
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
            .setContentTitle("Микрофон для ПК")
            .setContentText(if (status.value.muted) "Без звука" else if (status.value.streaming) "Передача звука" else "Подключение…")
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
