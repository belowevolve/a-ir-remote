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
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
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
                editor.onValueChanged = null
            }
            val first = TvIdentity(targetContext).certificate
            val second = TvIdentity(targetContext).certificate
            check(first.encoded.contentEquals(second.encoded)) { "Identity changed on reopen" }
            check(first.subjectX500Principal.name.startsWith("CN=Air Remote/"))
            check(first.subjectX500Principal.name != "CN=Air Remote")
            results.putString("stream", "IME batch, suggestion replacement, selection and stable unique certificate: PASS\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
