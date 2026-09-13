package dev.air.remote

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Keeps Backspace observable even when the TV has text that is absent locally. */
class RemoteEditText(context: Context) : EditText(context) {
    var onValueChanged: ((TextFieldValue) -> Unit)? = null
    var onBoundaryDelete: ((Int) -> Unit)? = null
    private var applyingRemoteValue = false
    private var requestedKeyboard = false
    private val publish = Runnable { publishValue() }

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
        removeCallbacks(publish)
        post(publish)
    }

    private fun publishValue() {
        removeCallbacks(publish)
        if (applyingRemoteValue) return
        val content = text?.toString().orEmpty()
        onValueChanged?.invoke(TextFieldValue(content, TextRange(
            selectionStart.coerceIn(0, content.length), selectionEnd.coerceIn(0, content.length),
        )))
    }

    fun applyValue(value: TextFieldValue) {
        if (text.toString() == value.text && selectionStart == value.selection.start && selectionEnd == value.selection.end) return
        applyingRemoteValue = true
        try {
            if (text.toString() != value.text) setText(value.text)
            setSelection(value.selection.start.coerceIn(0, length()), value.selection.end.coerceIn(0, length()))
        } finally {
            applyingRemoteValue = false
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(connection, false) {
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
                if (beforeLength > 0 && afterLength == 0 && selectionStart == 0 && selectionEnd == 0) {
                    publishValue()
                    onBoundaryDelete?.invoke(beforeLength)
                    return true
                }
                val result = super.deleteSurroundingText(beforeLength, afterLength)
                publishValue()
                return result
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && afterLength == 0 && selectionStart == 0 && selectionEnd == 0) {
                    publishValue()
                    onBoundaryDelete?.invoke(beforeLength)
                    return true
                }
                val result = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                publishValue()
                return result
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_DEL && selectionStart == 0 && selectionEnd == 0) {
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
            post { context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && selectionStart == 0 && selectionEnd == 0) {
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
