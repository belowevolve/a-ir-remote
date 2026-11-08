package belowevolve.airremote

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Device-side checks use real EditText/InputConnection and Android Keystore. */
class InputRegressionRunner : Instrumentation() {
    private var tvHost: String? = null
    private var tvSubmit = false
    private var tvRawKey: String? = null
    private var tvRawLaunchUri: String? = null
    private var tvRawDelayMs: String? = null
    private var clipboardFixture = false
    private var tvLifecycleFixture = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        tvHost = arguments?.getString("tvHost")
        tvSubmit = arguments?.getString("tvSubmit") == "true"
        tvRawKey = arguments?.getString("tvRawKey")
        tvRawLaunchUri = arguments?.getString("tvRawLaunchURI")
        tvRawDelayMs = arguments?.getString("tvRawDelayMs")
        clipboardFixture = arguments?.getString("clipboardFixture") == "true"
        tvLifecycleFixture = arguments?.getString("tvLifecycleFixture") == "true"
        start()
    }
    override fun onStart() {
        val results = Bundle()
        try {
            if (tvLifecycleFixture) {
                checkTvConnectionLifecycle()
                results.putString("stream", "TV lifecycle: background handshake, retained TLS session, immediate keys, held-key release, silent reconnect and cleanup: PASS\n")
                finish(Activity.RESULT_OK, results)
                return
            }
            if (clipboardFixture) {
                require(tvHost == null && tvRawKey == null && tvRawLaunchUri == null) {
                    "Clipboard fixture cannot be combined with TV probes"
                }
                checkClipboardFixture(results)
                finish(Activity.RESULT_OK, results)
                return
            }
            if (tvRawKey != null || tvRawLaunchUri != null) {
                checkTvRawCommand(results)
                finish(Activity.RESULT_OK, results)
                return
            }
            runOnMainSync {
                val editor = RemoteEditText(targetContext).apply { inputType = InputType.TYPE_CLASS_TEXT }
                val updates = mutableListOf<TextFieldValue>()
                editor.onValueChanged = { updates += it }
                val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
                connection.setComposingText("при", 1)
                check(editor.text.toString() == "при")
                check(updates.last().composition != null)
                val beforeSuggestion = updates.last()
                updates.clear()
                connection.beginBatchEdit()
                connection.beginBatchEdit()
                connection.commitText("привет", 1)
                editor.applyValue(beforeSuggestion) // stale Compose update during the batch
                connection.commitText(" ", 1)
                check(updates.isEmpty()) { "Published intermediate suggestion text" }
                connection.endBatchEdit()
                check(updates.isEmpty()) { "Published inside a nested batch" }
                connection.endBatchEdit()
                check(updates.size == 1)
                check(updates.single().text == "привет ") { updates.toString() }
                check(updates.single().selection == TextRange(7))
                check(updates.single().composition == null)
                // Replace the selected word, preserving text after the selection.
                connection.setSelection(0, 6)
                connection.beginBatchEdit()
                connection.commitText("здравствуйте", 1)
                connection.endBatchEdit()
                check(editor.text.toString() == "здравствуйте ")
                var boundaryDeletes = 0
                editor.onBoundaryDelete = { boundaryDeletes += it }
                connection.setSelection(0, 0)
                connection.deleteSurroundingText(1, 0)
                check(boundaryDeletes == 0) { "Backspace at start of existing text deleted on TV" }
                check(editor.text.toString() == "здравствуйте ")
                editor.onValueChanged = null
            }
            checkDpadInput()
            checkImeBatchRegression()
            tvHost?.let(::checkTvEditing)
            val first = TvIdentity(targetContext).certificate
            val second = TvIdentity(targetContext).certificate
            check(first.encoded.contentEquals(second.encoded)) { "Identity changed on reopen" }
            check(first.subjectX500Principal.name.startsWith("CN=Air Remote/"))
            check(first.subjectX500Principal.name != "CN=Air Remote")
            results.putString("stream", "IME batch, suggestion replacement, selection, stable unique certificate, D-pad swipe/hold/release/cancellation and power button tap/hold/cancellation: PASS\n")
            if (tvHost != null) results.putString("stream", results.getString("stream") + "TV authoritative email + middle insertion/deletion + cursor after reconnect: PASS\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", results.getString("stream", "") + error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    /** Opt-in observation only: bypasses RemoteModel and automatic wake entirely. */
    private fun checkTvRawCommand(results: Bundle) {
        val host = requireNotNull(tvHost?.takeIf { it.isNotBlank() }) { "tvHost is required" }
        require((tvRawKey != null) != (tvRawLaunchUri != null)) {
            "Choose one command: tvRawKey or tvRawLaunchURI"
        }
        val key = tvRawKey?.let { raw ->
            requireNotNull(raw.toIntOrNull()?.takeIf { it >= 0 }) { "tvRawKey must be a nonnegative Android keycode" }
        }
        val uri = tvRawLaunchUri?.also { require(it.isNotBlank()) { "tvRawLaunchURI is empty" } }
        val delayMs = tvRawDelayMs?.let { raw ->
            requireNotNull(raw.toLongOrNull()?.takeIf { it in 0..5_000 }) { "tvRawDelayMs must be 0..5000" }
        } ?: 0L
        val timeoutMs = 30_000 + delayMs
        data class Power(val atMs: Long, val on: Boolean)
        val observations = LinkedBlockingQueue<Power>()
        val failure = AtomicReference<Throwable>()
        val closing = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val client = TvClient(TvIdentity(targetContext))
        val startedAt = SystemClock.elapsedRealtime()
        fun report(line: String) {
            results.putString("stream", results.getString("stream", "") + line + "\n")
            sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
        }
        val reader = Thread({
            try {
                client.listen(host, {}, {}, ready = { powered ->
                    observations.offer(Power(SystemClock.elapsedRealtime() - startedAt, powered))
                })
            } catch (error: Throwable) {
                if (!closing.get()) failure.set(error)
            }
        }, "TvRawProbeReader").apply { isDaemon = true }
        // Socket writes are blocking; the independent deadline also closes a stalled writer.
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "TvRawProbeDeadline").apply { isDaemon = true }
        }
        val deadline = watchdog.schedule({
            timedOut.set(true)
            client.close()
        }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            reader.start()
            var before: Power? = null
            while (before == null && SystemClock.elapsedRealtime() - startedAt < 20_000) {
                failure.get()?.let { throw it }
                before = observations.poll(100, TimeUnit.MILLISECONDS)
            }
            var latest = checkNotNull(before) { "No TV ready/power callback within 20 seconds" }
            report("RAW ready: screenPower=${latest.on}, callbackAtMs=${latest.atMs}, delayMs=$delayMs")
            if (delayMs > 0) Thread.sleep(delayMs)
            failure.get()?.let { throw it }
            check(!timedOut.get()) { "Raw probe exceeded ${timeoutMs}ms deadline" }
            while (true) latest = observations.poll() ?: break
            report("RAW probe: screenPowerBefore=${latest.on}, callbackAtMs=${latest.atMs}; automaticWake=false")
            val calledAt = SystemClock.elapsedRealtime() - startedAt
            if (key != null) client.sendKey(key) else client.launchAppLink(checkNotNull(uri))
            report("RAW command sent: ${key?.let { "key=$it" } ?: "launchURI"}, " +
                "callAtMs=$calledAt, completedAtMs=${SystemClock.elapsedRealtime() - startedAt}")
            var after: Power? = null
            val observeUntil = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < observeUntil) {
                check(!timedOut.get()) { "Raw probe exceeded ${timeoutMs}ms deadline" }
                failure.get()?.let { throw it }
                val observation = observations.poll(100, TimeUnit.MILLISECONDS) ?: continue
                after = observation
                report("RAW power callback: screenPower=${observation.on}, callbackAtMs=${observation.atMs}")
            }
            report("RAW observation complete: screenPowerAfter=${after?.on ?: "unknown_no_new_callback"}, " +
                "lastReportedPower=${after?.on ?: latest.on}; command delivery/visible wake requires TV observation")
        } finally {
            closing.set(true)
            deadline.cancel(false)
            watchdog.shutdownNow()
            client.close()
            reader.join(3_000)
            check(!reader.isAlive) { "Raw probe reader did not stop after socket close" }
        }
    }

    /** Opt-in integration check. Requires an already paired TV with a focused search field. */
    private fun checkTvEditing(host: String) {
        val updates = java.util.concurrent.LinkedBlockingQueue<KeyboardUpdate>()
        val client = TvClient(TvIdentity(targetContext))
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        fun connect(): Thread = Thread {
            try { client.listen(host, { updates.offer(it) }, {}, ready = {}) }
            catch (error: Throwable) { failure.set(error) }
        }.apply { start() }
        fun awaitText(): KeyboardUpdate {
            val deadline = android.os.SystemClock.elapsedRealtime() + 8000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val update = updates.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (update.show) return update
            }
            error("No focused TV field; connection failure=${failure.get()}")
        }
        var reader = connect()
        try {
            val initial = awaitText()
            val epoch = initial.epoch
            // Simulate composing a prefix, accepting an email suggestion, then editing its middle.
            val values = listOf(KeyboardText("sample"), KeyboardText("sample322@example.com"),
                KeyboardText("sample322@example.com", 6), KeyboardText("sampleX322@example.com", 7),
                KeyboardText("sample322@example.com", 6))
            values.forEachIndexed { index, value -> client.editKeyboard(epoch, index + 1L, value) }
            // The service may suppress echoes to the editing client. Reconnect
            // to obtain an independent authoritative snapshot instead.
            client.close()
            reader.join(3000)
            updates.clear()
            failure.set(null)
            reader = connect()
            val state = awaitText()
            check(state.value.text == "sample322@example.com") { "TV text differs from the synthetic test input" }
            check(state.value.start == 6 && state.value.end == 6) { "TV cursor differs from the expected position" }
            if (tvSubmit) client.submitKeyboard(state.epoch)
        } finally {
            client.close()
            reader.join(3000)
        }
    }

}
