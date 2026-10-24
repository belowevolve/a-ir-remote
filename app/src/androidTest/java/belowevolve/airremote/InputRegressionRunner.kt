package belowevolve.airremote

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Device-side checks use real EditText/InputConnection and Android Keystore. */
class InputRegressionRunner : Instrumentation() {
    private var tvHost: String? = null
    private var tvSubmit = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        tvHost = arguments?.getString("tvHost")
        tvSubmit = arguments?.getString("tvSubmit") == "true"
        start()
    }
    override fun onStart() {
        val results = Bundle()
        try {
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
            tvHost?.let(::checkTvEditing)
            val first = TvIdentity(targetContext).certificate
            val second = TvIdentity(targetContext).certificate
            check(first.encoded.contentEquals(second.encoded)) { "Identity changed on reopen" }
            check(first.subjectX500Principal.name.startsWith("CN=Air Remote/"))
            check(first.subjectX500Principal.name != "CN=Air Remote")
            results.putString("stream", "IME batch, suggestion replacement, selection and stable unique certificate: PASS\n")
            if (tvHost != null) results.putString("stream", results.getString("stream") + "TV authoritative email + middle insertion/deletion + cursor after reconnect: PASS\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
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
