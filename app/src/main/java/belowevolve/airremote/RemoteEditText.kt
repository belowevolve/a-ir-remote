package belowevolve.airremote

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Publishes complete native IME batches and preserves composition during Compose updates. */
class RemoteEditText(context: Context) : EditText(context) {
    var onValueChanged: ((TextFieldValue) -> Unit)? = null
    var onBoundaryDelete: ((Int) -> Unit)? = null
    private var applyingRemoteValue = false
    private var requestedKeyboard = false
    private var batchDepth = 0
    private var pendingPublish = false
    private var lastPublished = TextFieldValue()
    private val publish = Runnable { publishValue() }
    private val atEmptyBoundary get() = length() == 0 && selectionStart == 0 && selectionEnd == 0

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { schedulePublish() }
        })
    }

    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        // The superclass may call this before our fields have been initialized.
        if (onValueChanged != null) schedulePublish()
    }

    private fun schedulePublish() {
        if (applyingRemoteValue) return
        pendingPublish = true
        removeCallbacks(publish)
        if (batchDepth == 0) post(publish)
    }

    fun publishValue() {
        removeCallbacks(publish)
        if (applyingRemoteValue) return
        if (batchDepth > 0) { pendingPublish = true; return }
        pendingPublish = false
        val content = text?.toString().orEmpty()
        val composingStart = BaseInputConnection.getComposingSpanStart(text)
        val composingEnd = BaseInputConnection.getComposingSpanEnd(text)
        lastPublished = TextFieldValue(content, TextRange(
            selectionStart.coerceIn(0, content.length), selectionEnd.coerceIn(0, content.length),
        ), if (composingStart >= 0 && composingEnd >= 0) TextRange(composingStart, composingEnd) else null)
        onValueChanged?.invoke(lastPublished)
    }

    fun applyValue(value: TextFieldValue) {
        // Compose can reapply its previous snapshot between IME callbacks. Never
        // overwrite an in-progress suggestion/batch with that stale snapshot.
        if ((pendingPublish || batchDepth > 0) && value.text == lastPublished.text && value.selection == lastPublished.selection) return
        if (text.toString() == value.text && selectionStart == value.selection.start && selectionEnd == value.selection.end) return
        applyingRemoteValue = true
        try {
            if (text.toString() != value.text) {
                text?.replace(0, length(), value.text)
                BaseInputConnection.removeComposingSpans(text)
            }
            setSelection(value.selection.start.coerceIn(0, length()), value.selection.end.coerceIn(0, length()))
        } finally {
            applyingRemoteValue = false
            lastPublished = value
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(connection, false) {
            override fun beginBatchEdit(): Boolean {
                batchDepth++
                return super.beginBatchEdit()
            }
            override fun endBatchEdit(): Boolean {
                val result = super.endBatchEdit()
                batchDepth = (batchDepth - 1).coerceAtLeast(0)
                if (batchDepth == 0 && pendingPublish) publishValue()
                return result
            }
            override fun finishComposingText(): Boolean {
                val result = super.finishComposingText()
                publishValue()
                return result
            }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val result = super.commitText(text, newCursorPosition)
                publishValue()
                return result
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val result = super.setComposingText(text, newCursorPosition)
                publishValue()
                return result
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                publishValue()
                return super.performEditorAction(editorAction)
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && atEmptyBoundary) {
                    publishValue()
                    onBoundaryDelete?.invoke(beforeLength)
                    return true
                }
                val result = super.deleteSurroundingText(beforeLength, afterLength)
                publishValue()
                return result
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && atEmptyBoundary) {
                    publishValue()
                    onBoundaryDelete?.invoke(beforeLength)
                    return true
                }
                val result = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                publishValue()
                return result
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_DEL && atEmptyBoundary) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        publishValue()
                        onBoundaryDelete?.invoke(1)
                    }
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && !requestedKeyboard) {
            requestedKeyboard = true
            requestFocus()
            post { context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0) }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && atEmptyBoundary) {
            publishValue()
            onBoundaryDelete?.invoke(1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(publish)
        super.onDetachedFromWindow()
    }
}
