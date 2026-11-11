package belowevolve.airremote

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in checks. Recognition fixtures never send commands to a real TV. */
class VoiceControlRegressionRunner : Instrumentation() {
    private var mode = "recognition"
    private var requestedCommand = LocalVoiceCommand.TV_ON
    private var requestedVolume: Int? = null
    private var requestedSeek: Int? = null
    private var frameSamples = OfflineVoiceRecognizer.FRAME_SAMPLES
    private var trailingSilenceSeconds = 0.25f
    private var airHomeSetting: Boolean? = null
    private var standbyMillis = 150_000L
    private var standbyCommand = LocalVoiceCommand.TV_ON
    private var wakeKey: Int? = null
    private var wolMac: String? = null
    private var wolBroadcast = "255.255.255.255"
    private var preWakePort: Int? = null
    private var connectionPort: Int? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: mode
        requestedCommand = arguments?.getString("command")?.let(LocalVoiceCommand::valueOf) ?: requestedCommand
        requestedVolume = arguments?.getString("volumePercent")?.toInt()
        requestedSeek = arguments?.getString("seekSeconds")?.toInt()
        frameSamples = arguments?.getString("frameSamples")?.toInt()?.also { require(it in 160..3200) } ?: frameSamples
        trailingSilenceSeconds = arguments?.getString("trailingSilence")?.toFloat()?.also { require(it in 0.2f..0.6f) } ?: trailingSilenceSeconds
        airHomeSetting = arguments?.getString("airHome")?.toBooleanStrict()
        standbyMillis = arguments?.getString("standbyMillis")?.toLong()?.also { require(it in 500..180_000) } ?: standbyMillis
        standbyCommand = arguments?.getString("standbyCommand")?.let(LocalVoiceCommand::valueOf)?.also {
            require(it in listOf(LocalVoiceCommand.TV_ON, LocalVoiceCommand.TV_TOGGLE))
        } ?: standbyCommand
        wakeKey = arguments?.getString("wakeKey")?.toInt()?.also { require(it == 26 || it == 224) }
        wolMac = arguments?.getString("wolMac")?.also { require(it.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))) }
        wolBroadcast = arguments?.getString("wolBroadcast") ?: wolBroadcast
        preWakePort = arguments?.getString("preWakePort")?.toInt()?.also { require(it == 6465) }
        connectionPort = arguments?.getString("connectionPort")?.toInt()?.also { require(it == 6465 || it == 6466) }
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val report = StringBuilder()
        try {
            val prefs = targetContext.getSharedPreferences("remote", 0)
            airHomeSetting?.let { enabled ->
                VoiceTvFeatures.setAirHome(targetContext, prefs.getString("host", "").orEmpty(), enabled)
            }
            report.appendLine("Saved TV: ${prefs.getString("name", "none")}, host=${prefs.getString("host", "none")}")
            when (mode) {
                "transportFixture" -> report.append(checkVoiceTransport())
                "features" -> report.appendLine("Air Home enabled=${VoiceTvFeatures.airHome(targetContext, prefs.getString("host", "").orEmpty())}")
                "captureRecovery" -> {
                    startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    runOnMainSync { VoiceControlService.start(targetContext) }
                    fun awaitCapture(predicate: (Triple<Long, Int, Boolean>) -> Boolean): Triple<Long, Int, Boolean> {
                        val deadline = SystemClock.elapsedRealtime() + 20_000
                        while (true) {
                            val state = runCatching { VoiceControlService.captureForRegression() }.getOrNull()
                            if (state != null && predicate(state)) return state
                            check(SystemClock.elapsedRealtime() < deadline) { "Capture not ready: $state ${VoiceControlService.status.value}" }
                            Thread.sleep(50)
                        }
                    }
                    awaitCapture { it.first > 0 }
                    uiAutomation.executeShellCommand("input keyevent 223").close()
                    repeat(2) {
                        val before = VoiceControlService.captureForRegression()
                        VoiceControlService.interruptCaptureForRegression()
                        val after = awaitCapture { it.first > before.first + 16_000 && it.second > before.second }
                        check(VoiceControlService.enabled(targetContext) && VoiceControlService.status.value.active)
                        report.appendLine("Locked-phone recorder restart ${it + 1}: $before -> $after PASS")
                    }
                    try {
                        // Fault injection: OEM Android denies changing sensor privacy while locked.
                        VoiceControlService.silenceForRegression(true)
                        awaitCapture { it.third }
                        Thread.sleep(5_000) // The former implementation disabled the feature after 3 s.
                        check(VoiceControlService.enabled(targetContext) && VoiceControlService.status.value.active)
                    } finally { VoiceControlService.silenceForRegression(null) }
                    val before = VoiceControlService.captureForRegression()
                    awaitCapture { !it.third && it.first > before.first + 16_000 }
                    report.appendLine("Simulated Android silencing > 3 s on real capture; setting retained; capture resumes: PASS")
                }
                "standbyProbe" -> {
                    startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    runOnMainSync { VoiceControlService.start(targetContext) }
                    val deadline = SystemClock.elapsedRealtime() + 20_000
                    while (!VoiceControlService.status.value.active || VoiceControlService.status.value.loading) {
                        check(SystemClock.elapsedRealtime() < deadline) { VoiceControlService.status.value.toString() }
                        Thread.sleep(50)
                    }
                    uiAutomation.executeShellCommand("input keyevent 223").close()
                    runBlocking {
                        connectionPort?.let { VoiceControlService.probePortForRegression(it) }
                        val trace = java.io.File(targetContext.filesDir, "standby-probe.txt")
                        trace.writeText("Foreground microphone started; phone locked; elapsed=${SystemClock.elapsedRealtime()}\n")
                        val off = VoiceControlService.executeTvForRegression(LocalVoiceCommand.TV_OFF)
                        report.appendLine("Before standby: $off")
                        trace.appendText("OFF: $off elapsed=${SystemClock.elapsedRealtime()}\n")
                        kotlinx.coroutines.delay(standbyMillis)
                        val at = SystemClock.elapsedRealtime()
                        trace.appendText("ON requested elapsed=$at\n")
                        preWakePort?.let { port ->
                            java.net.Socket().use { socket ->
                                socket.connect(java.net.InetSocketAddress(prefs.getString("host", ""), port), 500)
                            }
                            trace.appendText("Wake port connected elapsed=${SystemClock.elapsedRealtime()}\n")
                        }
                        wolMac?.let { mac ->
                            val bytes = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
                            val packet = ByteArray(102) { if (it < 6) 0xff.toByte() else bytes[(it - 6) % 6] }
                            java.net.DatagramSocket().use { socket ->
                                socket.broadcast = true
                                socket.send(java.net.DatagramPacket(packet, packet.size, java.net.InetAddress.getByName(wolBroadcast), 9))
                            }
                            trace.appendText("WOL sent elapsed=${SystemClock.elapsedRealtime()}\n")
                            kotlinx.coroutines.delay(200)
                        }
                        val on = wakeKey?.let { VoiceControlService.probeWakeKeyForRegression(it) }
                            ?: VoiceControlService.executeTvForRegression(standbyCommand)
                        report.appendLine("After ${standbyMillis}ms standby: $on, elapsedMs=${SystemClock.elapsedRealtime() - at}")
                        trace.appendText("ON completed elapsed=${SystemClock.elapsedRealtime()}\n")
                    }
                }
                "irWake" -> {
                    val ir = targetContext.getSystemService(android.hardware.ConsumerIrManager::class.java)
                    check(ir?.hasIrEmitter() == true) { "No IR emitter" }
                    val signal = requireNotNull(IrSignal.powerFor(prefs.getString("name", "").orEmpty()))
                    ir.transmit(signal.first, signal.second)
                    report.appendLine("One verified IR power pulse sent")
                }
                "start" -> {
                    startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    runOnMainSync { VoiceControlService.start(targetContext) }
                    val deadline = SystemClock.elapsedRealtime() + 20_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val state = VoiceControlService.status.value
                        if (state.active && !state.loading) break
                        Thread.sleep(100)
                    }
                    check(VoiceControlService.status.value.active && !VoiceControlService.status.value.loading) {
                        VoiceControlService.status.value.toString()
                    }
                    report.appendLine("Local voice service started: PASS")
                }
                "stop" -> {
                    runOnMainSync { VoiceControlService.stop(targetContext) }
                    report.appendLine("Local voice service stopped: PASS")
                }
                "tvOn", "action", "wakeLaunch", "mediaProbe" -> runBlocking {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val tv = VoiceTvController(targetContext, scope)
                    try {
                        if (mode == "mediaProbe") {
                            tv.turnOn()
                            for (command in listOf(LocalVoiceCommand.PAUSE, LocalVoiceCommand.PLAY,
                                LocalVoiceCommand.NEXT, LocalVoiceCommand.PREVIOUS)) {
                                val at = SystemClock.elapsedRealtime()
                                tv.execute(command)
                                report.appendLine("MEDIA,$command,sendMs=${SystemClock.elapsedRealtime() - at}")
                            }
                        } else {
                        if (mode == "wakeLaunch") {
                            require(requestedCommand in listOf(LocalVoiceCommand.FILM, LocalVoiceCommand.YOUTUBE, LocalVoiceCommand.PC))
                            report.appendLine("Before launch: ${tv.execute(LocalVoiceCommand.TV_OFF)}")
                        }
                        report.appendLine("Real TV: ${tv.execute(VoiceCommandRequest(requestedCommand, requestedVolume, requestedSeek))}")
                        if (mode == "wakeLaunch") report.appendLine("Repeated launch: ${tv.execute(requestedCommand)}")
                        }
                    }
                    finally { tv.close(); scope.cancel() }
                }
                else -> {
                    val loadAt = SystemClock.elapsedRealtime()
                    val observations = mutableListOf<String>()
                    val failures = mutableListOf<String>()
                    OfflineVoiceRecognizer(targetContext, observations::add, trailingSilenceSeconds).use { recognizer ->
                        report.appendLine("Model load: ${SystemClock.elapsedRealtime() - loadAt}ms")
                        listOf(
                            "air-tv" to listOf(LocalVoiceCommand.TV_TOGGLE),
                            "air-film" to listOf(LocalVoiceCommand.FILM),
                            "air-youtube" to listOf(LocalVoiceCommand.YOUTUBE),
                            "air-pc" to emptyList(),
                            "air-on" to listOf(LocalVoiceCommand.TV_ON),
                            "air-off" to listOf(LocalVoiceCommand.TV_OFF),
                            "air-stop" to listOf(LocalVoiceCommand.PAUSE),
                            "air-ok" to listOf(LocalVoiceCommand.OK),
                            "air-okay" to listOf(LocalVoiceCommand.OK),
                            "air-back" to listOf(LocalVoiceCommand.BACK),
                            "air-no-back" to emptyList(),
                            "air-back-cancel" to emptyList(),
                            "air-seek-15" to listOf(LocalVoiceCommand.SEEK),
                            "air-seek-back-5" to listOf(LocalVoiceCommand.SEEK),
                            "air-seek-seconds" to listOf(LocalVoiceCommand.SEEK),
                            "air-seek-default" to listOf(LocalVoiceCommand.SEEK),
                            "air-seek-default-back" to listOf(LocalVoiceCommand.SEEK),
                            "air-rewind-default" to listOf(LocalVoiceCommand.SEEK),
                            "air-rewind-5" to listOf(LocalVoiceCommand.SEEK),
                            "air-rewind-seconds" to listOf(LocalVoiceCommand.SEEK),
                            "air-no-rewind" to emptyList(),
                            "air-rewind-incomplete" to emptyList(),
                            "air-rewind-cancel" to emptyList(),
                            "air-no-seek" to emptyList(),
                            "air-seek-incomplete" to emptyList(),
                            "air-seek-cancel" to emptyList(),
                            "air-comp" to listOf(LocalVoiceCommand.PC),
                            "air-pause" to listOf(LocalVoiceCommand.PAUSE),
                            "air-play" to listOf(LocalVoiceCommand.PLAY),
                            "air-continue" to listOf(LocalVoiceCommand.PLAY),
                            "air-next" to listOf(LocalVoiceCommand.PLAY),
                            "air-next-item" to listOf(LocalVoiceCommand.NEXT),
                            "air-next-male" to listOf(LocalVoiceCommand.NEXT),
                            "air-previous" to listOf(LocalVoiceCommand.PREVIOUS),
                            "air-louder" to listOf(LocalVoiceCommand.VOLUME_UP),
                            "air-quieter" to listOf(LocalVoiceCommand.VOLUME_DOWN),
                            "air-louder-two" to listOf(LocalVoiceCommand.VOLUME_UP),
                            "air-quieter-three" to listOf(LocalVoiceCommand.VOLUME_DOWN),
                            "air-louder-cancel" to emptyList(),
                            "air-louder-invalid" to emptyList(),
                            "air-volume-zero" to listOf(LocalVoiceCommand.VOLUME_SET),
                            "air-volume-ten" to listOf(LocalVoiceCommand.VOLUME_SET),
                            "air-volume-45" to listOf(LocalVoiceCommand.VOLUME_SET),
                            "air-volume-45-pause" to listOf(LocalVoiceCommand.VOLUME_SET),
                            "air-volume-100" to listOf(LocalVoiceCommand.VOLUME_SET),
                            "air-volume-invalid" to emptyList(),
                            "air-volume-no" to emptyList(),
                            "air-stop-listening" to listOf(LocalVoiceCommand.STOP_LISTENING),
                            "air-negation" to emptyList(),
                            "air-no-film" to emptyList(),
                            "air-unaddressed" to emptyList(),
                            "air-only" to emptyList(),
                            "air-goodbye" to emptyList(),
                            "turn-on" to emptyList(),
                            "unaddressed" to emptyList(),
                            "negation" to emptyList(),
                            "stop" to emptyList(),
                        ).forEach { (name, expected) ->
                            val bytes = context.assets.open("voice/$name.pcm").use { it.readBytes() }
                            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val samples = ShortArray(buffer.remaining() + 16_000)
                            buffer.get(samples, 0, buffer.remaining()) // Leave room for the endpointer's ambiguity fallback.
                            val commands = mutableListOf<LocalVoiceCommand>()
                            val levels = mutableListOf<Int>()
                            val offsets = mutableListOf<Int>()
                            val steps = mutableListOf<Int>()
                            recognizer.reset()
                            observations.clear()
                            val startedAt = SystemClock.elapsedRealtime()
                            var detectedAtMs: Int? = null
                            var speechEndMs: Int? = null
                            var offset = 0
                            while (offset < samples.size) {
                                val end = minOf(offset + frameSamples, samples.size)
                                recognizer.accept(samples.copyOfRange(offset, end), end - offset)?.let {
                                    commands += it.command
                                    it.volumePercent?.let(levels::add)
                                    it.seekSeconds?.let(offsets::add)
                                    steps += it.volumeSteps
                                    detectedAtMs = end / 16
                                    val words = org.json.JSONObject(observations.last()).getJSONArray("result")
                                    speechEndMs = (words.getJSONObject(words.length() - 1).getDouble("end") * 1000).toInt()
                                }
                                offset = end
                            }
                            report.appendLine("$name: $commands, audioMs=${samples.size / 16}, processingMs=${SystemClock.elapsedRealtime() - startedAt}")
                            if (mode == "latency" && detectedAtMs != null)
                                report.appendLine("LATENCY,$name,frameSamples=$frameSamples,endPauseMs=${detectedAtMs!! - speechEndMs!!}")
                            if (commands != expected) failures += "$name expected $expected, got $commands; recognizer=$observations"
                            val expectedLevel = mapOf("air-volume-zero" to 0, "air-volume-ten" to 10, "air-volume-45-pause" to 45,
                                "air-volume-45" to 45, "air-volume-100" to 100)[name]
                            if (expectedLevel != null && levels != listOf(expectedLevel))
                                failures += "$name expected volume $expectedLevel, got $levels"
                            val expectedSteps = mapOf("air-louder-two" to 2, "air-quieter-three" to 3,
                                "air-louder" to 1, "air-quieter" to 1)[name]
                            if (expectedSteps != null && steps != listOf(expectedSteps))
                                failures += "$name expected $expectedSteps steps, got $steps"
                            val expectedSeek = mapOf("air-seek-15" to 900, "air-seek-back-5" to -300,
                                "air-seek-seconds" to 21, "air-seek-default" to 10, "air-seek-default-back" to -10,
                                "air-rewind-default" to -10, "air-rewind-5" to -300, "air-rewind-seconds" to -21)[name]
                            if (expectedSeek != null && offsets != listOf(expectedSeek))
                                failures += "$name expected seek $expectedSeek, got $offsets"
                        }
                        check(failures.isEmpty()) { failures.joinToString("\n") }
                    }
                    report.appendLine("Offline commands, unaddressed speech and negation: PASS")
                }
            }
            result.putString("stream", report.toString())
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", report.toString() + error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
