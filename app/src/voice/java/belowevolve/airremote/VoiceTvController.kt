package belowevolve.airremote

import android.content.Context
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel

private const val VOICE_TV_TAG = "AirRemoteVoice"

/** Keeps the authenticated remote ready while the microphone service owns the session. */
internal class VoiceTvController(context: Context, scope: CoroutineScope) : AutoCloseable {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val identity = TvIdentity(context)
    // This session lives across screen-off. Google's authenticated wake endpoint
    // stays allowed in Low Power Standby, unlike the regular control endpoint.
    // Older TVs fall back to 6466 and remember it for subsequent reconnects.
    private val client = TvClient(identity, initialPort = 6465)
    @Volatile private var ready = CompletableDeferred<Unit>()
    @Volatile private var screenOn = false
    @Volatile private var powerRevision = 0L
    @Volatile private var connectedHost = ""
    @Volatile private var preferredPort: Int? = null
    private var lastSleepRequestAt = Long.MIN_VALUE
    private val reconnectSignal = Channel<Unit>(Channel.CONFLATED)
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "host") client.close()
    }.also(prefs::registerOnSharedPreferenceChangeListener)
    private val reader = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val pending = CompletableDeferred<Unit>()
            ready = pending
            val host = prefs.getString("host", "").orEmpty()
            try {
                if (host.isNotBlank() && identity.hasPin(host)) {
                    connectedHost = host
                    client.listen(host, {}, {}, ready = { on ->
                        screenOn = on
                        powerRevision++
                        pending.complete(Unit)
                    }, preferredPort = preferredPort)
                }
            } catch (error: CancellationException) {
                if (!currentCoroutineContext().isActive) throw error
            }
            catch (error: Exception) { android.util.Log.d(VOICE_TV_TAG, "TV connection unavailable", error) }
            finally {
                ready = CompletableDeferred()
                connectedHost = ""
                client.close()
            }
            withTimeoutOrNull(3_000) { reconnectSignal.receive() }
        }
    }

    suspend fun turnOn(): String = execute(LocalVoiceCommand.TV_ON)

    internal suspend fun probePortForRegression(port: Int) {
        check(BuildConfig.DEBUG) { "Debug-only wake probe" }
        require(port == 6465 || port == 6466)
        preferredPort = port
        reconnectNow()
        awaitConnection(prefs.getString("host", "").orEmpty())
    }

    internal suspend fun probeWakeKeyForRegression(code: Int): String = withContext(Dispatchers.IO) {
        check(BuildConfig.DEBUG) { "Debug-only wake probe" }
        require(code == 26 || code == 224)
        awaitConnection(prefs.getString("host", "").orEmpty())
        check(!screenOn) { "Wake probe requires confirmed standby" }
        client.sendKey(code)
        check(withTimeoutOrNull(20_000) {
            while (!screenOn) delay(10)
            true
        } == true) { "Single key $code did not receive a screen-on confirmation" }
        "Телевизор включён (один код $code)"
    }

    suspend fun execute(command: LocalVoiceCommand): String = execute(VoiceCommandRequest(command))

    suspend fun execute(request: VoiceCommandRequest): String = withContext(Dispatchers.IO) {
        try { executeConnected(request) }
        catch (error: IOException) {
            // Power transitions can invalidate a vendor wake session without a socket EOF.
            // Do not keep using its stale power state after an unconfirmed command.
            reconnectNow()
            throw error
        }
    }

    private fun reconnectNow() {
        ready = CompletableDeferred()
        connectedHost = ""
        client.close()
        reconnectSignal.trySend(Unit)
    }

    private suspend fun awaitConnection(host: String) {
        check(withTimeoutOrNull(20_000) {
            while (!ready.isCompleted || connectedHost != host) delay(10)
            true
        } == true) {
            if (lastSleepRequestAt != Long.MIN_VALUE)
                "После команды выключения ТВ не отвечает по сети. Проверьте сетевой режим ожидания или включите ТВ ИК-пультом"
            else "Нет соединения с ТВ по сети. Проверьте питание и Wi-Fi"
        }
    }

    private suspend fun executeConnected(request: VoiceCommandRequest): String {
        val command = request.command
        fun trace(stage: String) {
            if (android.util.Log.isLoggable(VOICE_TV_TAG, android.util.Log.DEBUG))
                android.util.Log.d(VOICE_TV_TAG, "PowerStage $stage command=$command on=$screenOn ready=${ready.isCompleted} elapsed=${android.os.SystemClock.elapsedRealtime()}")
        }
        trace("requested")
        val host = prefs.getString("host", "").orEmpty()
        check(host.isNotBlank() && identity.hasPin(host)) { "Сначала подключи телевизор в пульте" }
        // Other remotes can change power without updating an older vendor session.
        // Re-authenticate on the cached working port to obtain a fresh power snapshot.
        val airHome = VoiceTvFeatures.airHome(context, host)
        val plan = VoiceActionPlan.forCommand(command, screenOn, airHome)
        // Power-dependent commands need a fresh snapshot. Ordinary media keys reuse
        // the healthy session so PLAY/PAUSE and volume avoid a TLS handshake each time.
        if (ready.isCompleted && connectedHost == host &&
            (screenOn && plan.awake == true && command != LocalVoiceCommand.TV_TOGGLE)) {
            reconnectNow()
        }
        // Observe the authenticated power state before interpreting the TV toggle.
        awaitConnection(host)
        trace("connection_ready")
        val confirmedPlan = VoiceActionPlan.forCommand(command, screenOn, airHome)
        if (command == LocalVoiceCommand.STOP_LISTENING) return "Голосовое управление остановлено"
        val power = confirmedPlan.awake
        if (power == null && !screenOn) return "Телевизор выключен"
        if (command == LocalVoiceCommand.TV_TOGGLE || (power == false && screenOn)) {
            val expectedOn = !screenOn
            if (expectedOn) awaitStandbyTransition()
            val revision = powerRevision
            trace("toggle_send")
            client.sendKey(26)
            if (!expectedOn) lastSleepRequestAt = android.os.SystemClock.elapsedRealtime()
            val report = withTimeoutOrNull(5_000) {
                while (powerRevision == revision && ready.isCompleted && connectedHost == host) delay(25)
                if (powerRevision != revision) screenOn else null
            }
            // A TV may close its network session before acknowledging standby.
            // The power write succeeded; do not replace it with a connection-settings error.
            if (report == true) confirmedPlan.helperLink?.let(client::launchAppLink)
            if (report == null) reconnectNow()
            return when (report) {
                true -> "Телевизор включён"
                false -> "Телевизор выключен"
                null -> if (expectedOn) "Команда включения отправлена; состояние ТВ не подтверждено"
                    else "Команда выключения отправлена; подтверждение от ТВ не получено"
            }
        }
        if (power == true) {
            // A fresh TLS session forgets the old session's standby transition timer.
            // Preserve it across our own rapid OFF -> app-launch command sequence.
            if (!screenOn) awaitStandbyTransition()
            ensureVoiceAwake(
                attemptWake = { timeout ->
                    trace("wake_attempt_$timeout")
                    client.ensureAwake(timeout)
                    trace("wake_ack")
                },
                refreshScreenOn = {
                    trace("refresh_start")
                    reconnectNow()
                    awaitConnection(host)
                    trace("refresh_ready")
                    android.util.Log.d(VOICE_TV_TAG, "Wake recheck: screenOn=$screenOn")
                    screenOn
                },
            )
        }
        confirmedPlan.helperLink?.let(client::launchAppLink)
        confirmedPlan.appLink?.let { link ->
            // Await the screen-on acknowledgement before sending the launch, even from standby.
            if (withTimeoutOrNull(1_000) {
                while (!screenOn) delay(10)
                true
            } != true) throw SocketTimeoutException("ТВ не подтвердил включение экрана")
            client.launchAppLink(link)
        }
        confirmedPlan.keyCode?.let { key -> repeat(request.volumeSteps) { client.sendKey(key) } }
        request.volumePercent?.let { client.launchAppLink("airhome://remote/volume?level=$it") }
        request.seekSeconds?.let { client.launchAppLink("airhome://remote/seek?seconds=$it") }
        return when (command) {
            LocalVoiceCommand.FILM -> "Команда: Фильм"
            LocalVoiceCommand.YOUTUBE -> "Команда: YouTube"
            LocalVoiceCommand.PC -> "Команда: Компьютер"
            LocalVoiceCommand.OK -> "Команда: ОК"
            LocalVoiceCommand.BACK -> "Команда: Назад"
            LocalVoiceCommand.PAUSE -> "Команда: Пауза"
            LocalVoiceCommand.PLAY -> "Команда: Плей"
            LocalVoiceCommand.NEXT -> "Команда: Следующее"
            LocalVoiceCommand.PREVIOUS -> "Команда: Предыдущее"
            LocalVoiceCommand.SEEK -> {
                val seconds = checkNotNull(request.seekSeconds)
                "Команда: ${if (seconds < 0) "Отмотай" else "Перемотай"} ${belowevolve.shared.design.SeekText.duration(seconds)}"
            }
            LocalVoiceCommand.VOLUME_UP -> volumeStepResult("Громче", request.volumeSteps)
            LocalVoiceCommand.VOLUME_DOWN -> volumeStepResult("Тише", request.volumeSteps)
            LocalVoiceCommand.VOLUME_SET -> "Команда: Громкость ${request.volumePercent}"
            else -> if (power == true) "Телевизор включён" else "Телевизор выключен"
        }
    }

    private fun volumeStepResult(direction: String, steps: Int): String = "Команда: $direction" +
        if (steps == 1) "" else " на $steps ${if (steps in 2..4) "шага" else "шагов"}"

    private suspend fun awaitStandbyTransition() {
        if (lastSleepRequestAt == Long.MIN_VALUE) return
        val remaining = 2_500 - (android.os.SystemClock.elapsedRealtime() - lastSleepRequestAt)
        if (remaining > 0) delay(remaining)
    }

    override fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        reader.cancel()
        client.close()
    }
}
