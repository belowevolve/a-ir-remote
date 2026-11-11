package belowevolve.airremote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal data class VoiceControlStatus(
    val active: Boolean = false,
    val loading: Boolean = false,
    val message: String = "Выключено",
)

/** User-enabled local listening; the notification always exposes an immediate stop action. */
class VoiceControlService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var samplesRead = 0L
    @Volatile private var lastSampleAt = 0L
    @Volatile private var lastPeak = 0
    @Volatile private var captureRestarts = 0
    @Volatile private var microphoneSilenced = false
    @Volatile private var lastCaptureIssue = "none"
    @Volatile private var regressionSilenced: Boolean? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tv: VoiceTvController? = null
    private val commandRunning = AtomicBoolean()
    private var lastCommandAt = Long.MIN_VALUE
    private var lastCommand: VoiceCommandRequest? = null
    @Volatile private var completedCommands = 0L
    @Volatile private var completedCommand = "none"
    @Volatile private var lastCommandDurationMs = 0L
    @Volatile private var attemptedCommand = "none"
    @Volatile private var lastCommandError = "none"

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || !enabled(this)) {
            stopListening(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Разреши доступ к микрофону"
            }
            check(!MicrophoneService.status.value.active) { "Сначала останови микрофон для ПК" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Голосовое управление", NotificationManager.IMPORTANCE_LOW),
            )
            mutableStatus.value = VoiceControlStatus(active = true, loading = true, message = "Подготовка…")
            if (android.os.Build.VERSION.SDK_INT >= 30)
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification())
            started = true
            if (BuildConfig.DEBUG) regressionService = this
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "AirRemote:LocalVoice",
            ).apply { setReferenceCounted(false); acquire() }
            scope.launch {
                try {
                    tv = VoiceTvController(this@VoiceControlService, scope)
                    if (BuildConfig.DEBUG) regressionController = tv
                    capture()
                } catch (_: CancellationException) { }
                catch (error: Throwable) {
                    Log.e(TAG, "Local voice stopped", error)
                    mutableStatus.value = VoiceControlStatus(message = if (error is LinkageError)
                        "Не удалось загрузить локальное распознавание. Обновите приложение"
                        else error.message ?: "Не удалось запустить голосовое управление")
                    // Keep the user's choice. Opening the app can retry a permission/model failure.
                    stopSelf()
                }
            }
            return START_STICKY
        } catch (error: Exception) {
            mutableStatus.value = VoiceControlStatus(message = error.message ?: "Не удалось запустить голосовое управление")
            stopSelf()
            return START_NOT_STICKY
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun capture() {
        OfflineVoiceRecognizer(this).use { recognizer ->
            var retryDelay = 500L
            while (currentCoroutineContext().isActive) {
                try {
                    captureWith(recognizer)
                    return
                } catch (error: CancellationException) { throw error }
                catch (error: SecurityException) { throw error }
                catch (error: IllegalStateException) {
                    currentCoroutineContext().ensureActive()
                    lastCaptureIssue = error.message ?: error.javaClass.simpleName
                    Log.w(TAG, "Reopening microphone: $lastCaptureIssue")
                    mutableStatus.value = VoiceControlStatus(active = true, loading = true,
                        message = "Восстанавливаю микрофон…")
                    notifyStatus()
                    delay(retryDelay)
                    captureRestarts++
                    recognizer.reset()
                    retryDelay = if (SystemClock.elapsedRealtime() - lastSampleAt < 5_000) 500L
                        else (retryDelay * 2).coerceAtMost(10_000)
                }
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun captureWith(recognizer: OfflineVoiceRecognizer) {
            val rate = OfflineVoiceRecognizer.SAMPLE_RATE
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Микрофон не поддерживает запись 16 кГц" }
            val input = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, rate * 2))
                .build()
            try {
                recorder = input
                check(input.state == AudioRecord.STATE_INITIALIZED) { "Микрофон недоступен" }
                if (!currentCoroutineContext().isActive) return
                input.startRecording()
                check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Не удалось включить микрофон" }
                mutableStatus.value = VoiceControlStatus(active = true, message = "Слушаю команды")
                notifyStatus()
                val samples = ShortArray(OfflineVoiceRecognizer.FRAME_SAMPLES)
                var lastSilenceCheck = 0L
                var wasSilenced = false
                Log.i(TAG, "Listening locally: sampleRate=$rate")
                while (currentCoroutineContext().isActive) {
                    val count = input.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (!currentCoroutineContext().isActive) break
                    check(count > 0) { "Ошибка записи микрофона: $count" }
                    samplesRead += count
                    lastSampleAt = SystemClock.elapsedRealtime()
                    lastPeak = (0 until count).maxOf { kotlin.math.abs(samples[it].toInt()) }
                    if (android.os.Build.VERSION.SDK_INT >= 29 && samplesRead - lastSilenceCheck >= rate / 4) {
                        lastSilenceCheck = samplesRead
                        val silenced = (if (BuildConfig.DEBUG) regressionSilenced else null)
                            ?: (input.activeRecordingConfiguration?.isClientSilenced == true)
                        microphoneSilenced = silenced
                        if (silenced != wasSilenced) {
                            // Android restores this recording automatically after a competing app
                            // releases the mic. Drop both the silence and the unfinished phrase.
                            recognizer.reset()
                            wasSilenced = silenced
                            if (silenced) lastCaptureIssue = "Microphone temporarily silenced by Android"
                            mutableStatus.value = VoiceControlStatus(active = true, message = if (silenced)
                                "Микрофон недоступен. Жду…"
                                else "Слушаю команды")
                            Log.i(TAG, "Microphone silenced=$silenced")
                            notifyStatus()
                        }
                    }
                    if (!wasSilenced) recognizer.accept(samples, count)?.let(::execute)
                }
            } finally {
                recorder = null
                runCatching { input.stop() }
                input.release()
                microphoneSilenced = false
            }
    }

    private fun execute(command: VoiceCommandRequest) {
        if (command.command == LocalVoiceCommand.STOP_LISTENING) {
            stop(this)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (command.command == LocalVoiceCommand.TV_TOGGLE && command == lastCommand && now - lastCommandAt < 2_500) return
        if (!commandRunning.compareAndSet(false, true)) return
        lastCommand = command
        lastCommandAt = now
        attemptedCommand = command.command.name
        lastCommandError = "none"
        scope.launch {
            try {
                val result = checkNotNull(tv).execute(command)
                val elapsed = SystemClock.elapsedRealtime() - now
                lastCommandDurationMs = elapsed
                completedCommand = command.command.name + (command.volumePercent?.let { ":$it" }
                    ?: command.seekSeconds?.let { ":${it}s" }
                    ?: command.volumeSteps.takeIf { it != 1 }?.let { ":${it}steps" } ?: "")
                completedCommands++
                Log.i(TAG, "$command completed in ${elapsed}ms")
                mutableStatus.update { it.copy(message = result) }
                notifyStatus()
                @Suppress("DEPRECATION")
                getSystemService(android.os.Vibrator::class.java)?.vibrate(
                    android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
                )
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                lastCommandError = error.message ?: error.javaClass.simpleName
                Log.w(TAG, "$command failed", error)
                mutableStatus.update { it.copy(message = error.message ?: "Не удалось выполнить команду") }
                notifyStatus()
            } finally { commandRunning.set(false) }
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, VoiceControlService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_remote_monochrome)
            .setContentTitle("Эйр слушает команды")
            .setContentText(mutableStatus.value.message)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
    }

    private fun notifyStatus() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) regressionController = null
        if (BuildConfig.DEBUG) regressionService = null
        scope.cancel()
        tv?.close()
        runCatching { recorder?.stop() }
        wakeLock?.let { if (it.isHeld) it.release() }
        mutableStatus.update {
            it.copy(active = false, loading = false,
                message = if (it.active || it.loading) "Остановлено" else it.message)
        }
        super.onDestroy()
    }

    override fun dump(fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        writer.println("LocalVoice active=${status.value.active} loading=${status.value.loading} enabled=${enabled(this)}")
        writer.println("samplesRead=$samplesRead lastSampleAgeMs=${SystemClock.elapsedRealtime() - lastSampleAt} peak=$lastPeak wakeLock=${wakeLock?.isHeld}")
        writer.println("message=${status.value.message}")
        writer.println("commandsCompleted=$completedCommands lastCommand=$completedCommand")
        writer.println("lastCommandDurationMs=$lastCommandDurationMs")
        writer.println("attemptedCommand=$attemptedCommand lastCommandError=$lastCommandError")
        writer.println("captureRestarts=$captureRestarts silenced=$microphoneSilenced lastCaptureIssue=$lastCaptureIssue")
    }

    companion object {
        @Volatile private var regressionController: VoiceTvController? = null

        internal suspend fun executeTvForRegression(command: LocalVoiceCommand): String {
            check(BuildConfig.DEBUG) { "Debug-only voice regression" }
            return checkNotNull(regressionController) { "Voice foreground service not ready" }.execute(command)
        }
        internal suspend fun probeWakeKeyForRegression(code: Int): String {
            check(BuildConfig.DEBUG) { "Debug-only wake probe" }
            return checkNotNull(regressionController) { "Voice foreground service not ready" }.probeWakeKeyForRegression(code)
        }
        internal suspend fun probePortForRegression(port: Int) {
            check(BuildConfig.DEBUG) { "Debug-only wake probe" }
            checkNotNull(regressionController) { "Voice foreground service not ready" }.probePortForRegression(port)
        }
        internal fun interruptCaptureForRegression() {
            check(BuildConfig.DEBUG) { "Debug-only microphone regression" }
            checkNotNull(regressionService?.recorder).stop()
        }
        internal fun captureForRegression(): Triple<Long, Int, Boolean> {
            check(BuildConfig.DEBUG) { "Debug-only microphone regression" }
            val service = checkNotNull(regressionService)
            return Triple(service.samplesRead, service.captureRestarts, service.microphoneSilenced)
        }
        internal fun silenceForRegression(value: Boolean?) {
            check(BuildConfig.DEBUG) { "Debug-only microphone regression" }
            checkNotNull(regressionService).regressionSilenced = value
        }
        @Volatile private var regressionService: VoiceControlService? = null
        private const val TAG = "AirRemoteVoice"
        private const val PREFS = "local_voice"
        private const val ENABLED = "enabled"
        private const val STOP = "belowevolve.airremote.STOP_LOCAL_VOICE"
        private const val CHANNEL = "local_voice"
        private const val NOTIFICATION = 32
        private val mutableStatus = MutableStateFlow(VoiceControlStatus())
        internal val status = mutableStatus.asStateFlow()

        fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ENABLED, false)

        fun start(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, true).apply()
            try { context.startForegroundService(Intent(context, VoiceControlService::class.java)) }
            catch (error: Exception) {
                mutableStatus.value = VoiceControlStatus(message = error.message ?: "Открой пульт для запуска микрофона")
            }
        }

        fun stop(context: Context) {
            stopListening(context)
            context.stopService(Intent(context, VoiceControlService::class.java))
        }

        private fun stopListening(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply()
            mutableStatus.value = VoiceControlStatus()
        }
    }
}
