package belowevolve.airremote

import android.app.Instrumentation
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.util.concurrent.atomic.AtomicReference

/** Shared TV/PC editor ordering, using actual framework InputConnections. */
internal fun Instrumentation.checkImeBatchRegression() {
    val failure = AtomicReference<Throwable>()
    runOnMainSync {
        try {
            val editor = RemoteEditText(targetContext).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                // Measured TextView edit completion consults its parent's layout
                // parameters even in this deliberately unattached SDK fixture.
                layoutParams = android.view.ViewGroup.LayoutParams(240, 600)
            }
            var latest = ""
            val submitted = mutableListOf<String>()
            val boundary = mutableListOf<Pair<String, Int>>()
            editor.onValueChanged = { latest = it.text }
            editor.onBoundaryDelete = { boundary += latest to it }
            editor.setOnEditorActionListener { _, _, _ ->
                editor.afterInputBatch { submitted += latest }
                true
            }
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            connection.beginBatchEdit()
            connection.beginBatchEdit()
            connection.commitText("final", 1)
            check(connection.performEditorAction(EditorInfo.IME_ACTION_SEND))
            connection.endBatchEdit()
            check(latest.isEmpty() && submitted.isEmpty()) { "Submitted inside an outer IME batch" }
            connection.endBatchEdit()
            check(latest == "final" && submitted == listOf("final")) { "Editor action lost final batch text" }

            val deletes: List<() -> Unit> = listOf(
                { connection.deleteSurroundingText(1, 0) },
                { connection.deleteSurroundingTextInCodePoints(1, 0) },
                { connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) },
                { editor.onKeyDown(KeyEvent.KEYCODE_DEL, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) },
            )
            for (delete in deletes) {
                editor.applyValue(TextFieldValue("x", TextRange(1)))
                latest = "x"
                boundary.clear()
                connection.beginBatchEdit()
                connection.setSelection(0, 1)
                connection.commitText("", 1)
                delete()
                check(boundary.isEmpty()) { "Boundary delete ran before the cleared fragment was published" }
                connection.endBatchEdit()
                check(boundary == listOf("" to 1)) { "Boundary delete observed stale text" }
            }

            connection.beginBatchEdit()
            connection.commitText("button", 1)
            editor.afterInputBatch { submitted += latest }
            check(submitted.size == 1)
            connection.endBatchEdit()
            check(submitted.last() == "button") { "Footer action ran before batch publication" }

            editor.applyValue(TextFieldValue())
            latest = ""
            submitted.clear()
            connection.beginBatchEdit()
            connection.commitText("x", 1)
            connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
            connection.closeConnection()
            check(latest == "x" && submitted.isEmpty()) { "Connection close replayed a deferred action or lost text" }
            val next = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            next.beginBatchEdit()
            next.setSelection(0, 1)
            next.commitText("xy", 1)
            connection.closeConnection() // A late close must not reset the new connection's batch.
            check(latest == "x") { "Stale close flushed the new connection's open batch" }
            next.endBatchEdit()
            check(latest == "xy") { "New connection remained stuck in the old batch depth" }
            check(!connection.commitText("stale", 1))
            next.commitText("z", 1)
            check(latest == "xyz")
            editor.applyValue(TextFieldValue())
            next.setComposingText("xy", 1)
            connection.closeConnection()
            check(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text) == 0) {
                "Stale close removed the new connection's composition"
            }
            next.commitText("xyz", 1)
            check(latest == "xyz") { "Stale close turned composition replacement into an append" }
            next.closeConnection()

            // TV wraps visually but still exposes its actual SEND action to the IME.
            editor.forceImeAction = true
            editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            editor.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            editor.applyValue(TextFieldValue("wrap ".repeat(80), TextRange(0)))
            editor.measure(android.view.View.MeasureSpec.makeMeasureSpec(240, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
            editor.layout(0, 0, 240, 600)
            check(editor.lineCount > 1) { "TV editor did not wrap visually" }
            val tvInfo = EditorInfo()
            val tvConnection = checkNotNull(editor.onCreateInputConnection(tvInfo))
            check(tvInfo.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEND)
            check(tvInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) { "TV multiline suppressed SEND" }
            submitted.clear()
            tvConnection.performEditorAction(EditorInfo.IME_ACTION_SEND)
            check(submitted.single() == editor.text.toString())
            tvConnection.closeConnection()

            // PC uses native Enter, ordered after the full outer IME batch.
            editor.forceImeAction = true
            editor.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            editor.applyValue(TextFieldValue())
            latest = ""
            submitted.clear()
            editor.setOnEditorActionListener { _, _, _ ->
                editor.afterInputBatch { submitted += editor.text.toString(); editor.applyValue(TextFieldValue()) }
                true
            }
            val pcInfo = EditorInfo()
            val pcConnection = checkNotNull(editor.onCreateInputConnection(pcInfo))
            check(pcInfo.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_GO)
            check(pcInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0)
            pcConnection.beginBatchEdit()
            pcConnection.beginBatchEdit()
            pcConnection.commitText("first", 1)
            pcConnection.performEditorAction(EditorInfo.IME_ACTION_GO)
            pcConnection.endBatchEdit()
            check(editor.text.toString() == "first" && submitted.isEmpty())
            pcConnection.endBatchEdit()
            check(submitted.single() == "first" && editor.text.isEmpty())
            pcConnection.commitText("next", 1)
            check(latest == "next")

            // Initial server context must preserve a fresh IME composition and the
            // following correction must replace it at its shifted UTF-16 position.
            editor.applyValue(TextFieldValue())
            pcConnection.setComposingText("прив", 1)
            val merged = PcTextField.initialize(PcTextSnapshot("prefix OLD suffix", 7, 10),
                TextFieldValue("прив", TextRange(4), TextRange(0, 4)))
            editor.afterInputBatch { editor.applyValue(merged) }
            check(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text) == 7)
            pcConnection.commitText("привет", 1)
            check(editor.text.toString() == "prefix привет suffix") { "Initial context broke autocorrection composition" }
            pcConnection.closeConnection()
            editor.onValueChanged = null
            editor.onBoundaryDelete = null
        } catch (error: Throwable) { failure.set(error) }
    }
    failure.get()?.let { throw it }
}
