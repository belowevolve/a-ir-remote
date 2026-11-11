package belowevolve.airremote

import android.app.Instrumentation
import android.os.SystemClock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** Real Android TLS/protobuf checks; no TV launcher or physical TV participates. */
internal fun Instrumentation.checkVoiceTransport(): String = runBlocking {
    val host = "127.0.0.1"
    val prefs = targetContext.getSharedPreferences("remote", 0)
    val pins = targetContext.getSharedPreferences("tv_identity", 0)
    val localVoice = targetContext.getSharedPreferences("local_voice", 0)
    val savedPrefs = prefs.all.toMap()
    val savedPins = pins.all.toMap()
    val savedVoice = localVoice.all.toMap()
    runOnMainSync { VoiceControlService.stop(targetContext) }
    val identity = TvIdentity(targetContext)
    identity.pin(host, identity.certificate)
    val alias = requireNotNull(pins.getString("$host:identity", null))
    val report = StringBuilder()
    suspend fun withController(server: LoopbackTv, check: suspend (VoiceTvController) -> Unit) {
        server.use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val controller = VoiceTvController(targetContext, scope)
            try { check(controller) }
            finally { controller.close(); scope.cancel() }
        }
    }
    try {
        check(prefs.edit().putString("host", host).putString("name", "Portable TV fixture")
            .putBoolean("auto_connect", false).putBoolean("air_home:$host", false).commit())
        // This TV does not even advertise app-link support. Ordinary keys must still work.
        val native = LoopbackTv(identity.certificate, alias, features = 1 or 2 or 32, automaticPower = true)
        withController(native) { controller ->
            for ((command, code) in listOf(LocalVoiceCommand.PAUSE to 127, LocalVoiceCommand.PLAY to 126,
                LocalVoiceCommand.NEXT to 87, LocalVoiceCommand.PREVIOUS to 88,
                LocalVoiceCommand.VOLUME_UP to 24, LocalVoiceCommand.VOLUME_DOWN to 25)) {
                controller.execute(command)
                check(native.awaitKey().keyCodeValue == code)
            }
            check(controller.execute(VoiceCommandRequest(LocalVoiceCommand.VOLUME_UP, volumeSteps = 2)) == "Команда: Громче на 2 шага")
            repeat(2) { check(native.awaitKey().keyCodeValue == 24) }
            check(native.noKey()) { "Extra volume-up press" }
            check(controller.execute(VoiceCommandRequest(LocalVoiceCommand.VOLUME_DOWN, volumeSteps = 3)) == "Команда: Тише на 3 шага")
            repeat(3) { check(native.awaitKey().keyCodeValue == 25) }
            check(native.noKey()) { "Extra volume-down press" }
            check(controller.execute(LocalVoiceCommand.TV_TOGGLE) == "Телевизор выключен")
            check(native.awaitKey().keyCodeValue == 26)
            check(controller.execute(LocalVoiceCommand.PAUSE) == "Телевизор выключен")
            check(native.noKey()) { "Media woke the TV" }
            check(controller.execute(LocalVoiceCommand.TV_TOGGLE) == "Телевизор включён")
            check(native.awaitKey().keyCodeValue == 26)
            check(native.connections.get() == 1) { "Power unnecessarily replaced a healthy TLS session" }
            check(native.appLinks.isEmpty()) { "Ordinary controls called an app helper" }
        }
        report.appendLine("Native power/media/volume, exact 2 up / 3 down presses without app-link support or Air Home; same TLS session: PASS")

        val youtube = LoopbackTv(identity.certificate, alias, automaticPower = true)
        withController(youtube) { controller ->
            controller.execute(LocalVoiceCommand.YOUTUBE)
            check(youtube.appLinks.poll(5, TimeUnit.SECONDS) == "https://www.youtube.com/tv")
            check(youtube.appLinks.isEmpty())
            check(youtube.noKey()) { "YouTube pressed a navigation key" }
        }
        report.appendLine("Native YouTube URL without a launcher helper: PASS")

        val sleep = LoopbackTv(identity.certificate, alias, features = 1 or 2 or 32,
            automaticPower = true, disconnectOnPower = true)
        withController(sleep) { controller ->
            val at = SystemClock.elapsedRealtime()
            val result = controller.execute(LocalVoiceCommand.TV_OFF)
            check(result.startsWith("Команда выключения отправлена")) { result }
            check(SystemClock.elapsedRealtime() - at < 5_000) { "Disconnect waited for a nonexistent power ACK" }
            check(sleep.awaitKey().keyCodeValue == 26)
            check(sleep.noKey()) { "Power was retried after disconnect" }
        }
        report.appendLine("TV disconnecting immediately after POWER without ACK: qualified success, no second POWER: PASS")

        val silent = LoopbackTv(identity.certificate, alias, features = 1 or 2 or 32,
            automaticPower = true, suppressPowerAck = true)
        withController(silent) { controller ->
            val result = controller.execute(LocalVoiceCommand.TV_OFF)
            check(result.startsWith("Команда выключения отправлена")) { result }
            check(silent.awaitKey().keyCodeValue == 26)
            check(controller.execute(LocalVoiceCommand.PAUSE) == "Телевизор выключен") {
                "Missing power ACK left the old screen-on snapshot active"
            }
            check(silent.noKey()) { "Power was retried or media was sent while asleep" }
            check(silent.connections.get() == 2) { "Unconfirmed power session count=${silent.connections.get()}, expected 2" }
        }
        report.appendLine("Power ACK missing on an open socket: refresh state without repeating POWER; media respects standby: PASS")
        report.toString()
    } finally {
        restorePreferences(prefs, savedPrefs)
        restorePreferences(pins, savedPins)
        restorePreferences(localVoice, savedVoice)
    }
}
