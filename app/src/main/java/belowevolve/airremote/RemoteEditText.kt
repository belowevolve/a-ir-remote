package belowevolve.airremote

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Publishes complete native IME batches and preserves composition during Compose updates. */
class RemoteEditText(context: Context) : EditText(context) {
    var onValueChanged: ((TextFieldValue) -> Unit)? = null
    var onBoundaryDelete: ((Int) -> Unit)? = null
    var onDismissKeyboard: (() -> Unit)? = null
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private var dismissKeyboard: OnBackInvokedCallback? = null
    var forceImeAction = false
    private var applyingRemoteValue = false
    private var requestedKeyboard = false
    private var keyboardAttempts = 0
    private var keyboardWindowFocused = false
    private val keyboardWindowFocus = ViewTreeObserver.OnWindowFocusChangeListener(::handleKeyboardWindowFocus)
    private var batchDepth = 0
    private var connectionGeneration = 0
    private var activeConnection: InputConnection? = null
    private val afterBatch = java.util.ArrayDeque<() -> Unit>()
    private var pendingPublish = false
    private var lastPublished = TextFieldValue()
    private val publish = Runnable { publishValue() }
    private val showKeyboard = object : Runnable {
        override fun run() {
            if (requestedKeyboard || !isAttachedToWindow || !hasWindowFocus() || !isEnabled) return
            if (!hasFocus()) {
                if (requestFocus()) postOnAnimation(this) else requestedKeyboard = true
                return
            }
            if (Build.VERSION.SDK_INT >= 30 && rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) {
                requestedKeyboard = true
                return
            }
            val manager = context.getSystemService(InputMethodManager::class.java)
            // Flush the newly focused native editor before requesting its IME.
            // showSoftInput's return value reports a request, not visibility.
            manager.isActive(this@RemoteEditText)
            if (Build.VERSION.SDK_INT >= 30) windowInsetsController?.show(WindowInsets.Type.ime())
            else manager.showSoftInput(this@RemoteEditText, 0)
            if (++keyboardAttempts < 4) postDelayed(this, 150)
            else requestedKeyboard = true
        }
    }
    private val atEmptyBoundary get() = length() == 0 && selectionStart == 0 && selectionEnd == 0

    fun showKeyboardWhenReady() {
        if (!isAttachedToWindow || !hasWindowFocus() || !isEnabled) return
        requestedKeyboard = false
        keyboardAttempts = 0
        requestFocus()
        removeCallbacks(showKeyboard)
        postOnAnimation(showKeyboard)
    }

    init {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((16 * resources.displayMetrics.density).toInt())
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

    /** Submit/boundary keys must follow the complete native IME batch, including nested edits. */
    fun afterInputBatch(action: () -> Unit): Boolean {
        if (batchDepth > 0) {
            // A broken IME must not create an unbounded action queue. Returning false
            // lets InputConnection callers observe that the extra action was refused.
            if (afterBatch.size >= 8) return false
            pendingPublish = true
            afterBatch.addLast(action)
        } else {
            publishValue()
            action()
        }
        return true
    }

    fun insertNewline() = afterInputBatch {
        val editable = text ?: return@afterInputBatch
        BaseInputConnection.removeComposingSpans(editable)
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, editable.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, editable.length)
        editable.replace(start, end, "\n")
        setSelection(start + 1)
        publishValue()
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
            value.composition?.let { composition ->
                activeConnection?.setComposingRegion(composition.min, composition.max)
            }
        } finally {
            applyingRemoteValue = false
            lastPublished = value
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // BaseInputConnection.closeConnection removes composing spans from the
        // shared Editable, so retire the previous target before creating a new one.
        activeConnection?.closeConnection()
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        if (forceImeAction) outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()
        val generation = ++connectionGeneration
        batchDepth = 0
        afterBatch.clear()
        if (pendingPublish) publishValue()
        return object : InputConnectionWrapper(connection, false) {
            private val current get() = generation == connectionGeneration
            override fun beginBatchEdit(): Boolean {
                if (!current) return false
                batchDepth++
                return super.beginBatchEdit()
            }
            override fun endBatchEdit(): Boolean {
                if (!current) return false
                val result = super.endBatchEdit()
                batchDepth = (batchDepth - 1).coerceAtLeast(0)
                if (batchDepth == 0) {
                    if (pendingPublish || afterBatch.isNotEmpty()) publishValue()
                    while (current && batchDepth == 0 && afterBatch.isNotEmpty()) afterBatch.removeFirst().invoke()
                }
                return result
            }
            override fun closeConnection() {
                if (!current) return
                connectionGeneration++
                activeConnection = null
                batchDepth = 0
                afterBatch.clear() // Closing an IME connection must never replay Enter/delete.
                super.closeConnection()
                publishValue()
            }
            override fun finishComposingText(): Boolean {
                if (!current) return false
                val result = super.finishComposingText()
                publishValue()
                return result
            }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!current) return false
                val result = super.commitText(text, newCursorPosition)
                publishValue()
                return result
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!current) return false
                val result = super.setComposingText(text, newCursorPosition)
                publishValue()
                return result
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                if (!current) return false
                if (batchDepth > 0) return afterInputBatch { if (current) super.performEditorAction(editorAction) }
                publishValue()
                return super.performEditorAction(editorAction)
            }
            override fun setSelection(start: Int, end: Int): Boolean {
                if (!current) return false
                return super.setSelection(start, end)
            }
            override fun setComposingRegion(start: Int, end: Int): Boolean {
                if (!current) return false
                val result = super.setComposingRegion(start, end)
                publishValue()
                return result
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (!current) return false
                if (beforeLength > 0 && afterLength == 0 && atEmptyBoundary) {
                    return afterInputBatch { if (current) onBoundaryDelete?.invoke(beforeLength) }
                }
                val result = super.deleteSurroundingText(beforeLength, afterLength)
                publishValue()
                return result
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (!current) return false
                if (beforeLength > 0 && afterLength == 0 && atEmptyBoundary) {
                    return afterInputBatch { if (current) onBoundaryDelete?.invoke(beforeLength) }
                }
                val result = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                publishValue()
                return result
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (!current) return false
                if (event.keyCode == KeyEvent.KEYCODE_DEL && atEmptyBoundary) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        return afterInputBatch { if (current) onBoundaryDelete?.invoke(1) }
                    }
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (batchDepth > 0) {
                        val copy = KeyEvent(event)
                        return afterInputBatch { if (current) super.sendKeyEvent(copy) }
                    }
                    publishValue()
                }
                return super.sendKeyEvent(event)
            }
        }.also { activeConnection = it }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        handleKeyboardWindowFocus(hasWindowFocus)
    }

    private fun handleKeyboardWindowFocus(focused: Boolean) {
        if (focused && !requestedKeyboard) {
            keyboardWindowFocused = true
            requestFocus()
            removeCallbacks(showKeyboard)
            postOnAnimation(showKeyboard)
        } else if (!focused && keyboardWindowFocused) {
            // Opening this dialog is the only automatic request. Returning from
            // another app must not reopen a keyboard the user already left.
            requestedKeyboard = true
            removeCallbacks(showKeyboard)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 33 && onDismissKeyboard != null) {
            val callback = OnBackInvokedCallback { onDismissKeyboard?.invoke() }
            dismissKeyboard = callback
            backDispatcher = findOnBackInvokedDispatcher()?.also {
                // This full-screen editor should leave in one Back, including while IME is shown.
                it.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            }
        }
        // A Compose AndroidView can be added after its Dialog already gained
        // focus, so do not rely on the child's first window-focus callback.
        viewTreeObserver.addOnWindowFocusChangeListener(keyboardWindowFocus)
        if (hasWindowFocus()) handleKeyboardWindowFocus(true)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30 && hasFocus() && hasWindowFocus() && insets.isVisible(WindowInsets.Type.ime())) {
            requestedKeyboard = true
            removeCallbacks(showKeyboard)
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && atEmptyBoundary) {
            return afterInputBatch { onBoundaryDelete?.invoke(1) }
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            if (batchDepth > 0) {
                val copy = KeyEvent(event)
                return afterInputBatch { super.onKeyDown(keyCode, copy) }
            }
            publishValue()
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && onDismissKeyboard != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                keyDispatcherState?.startTracking(event, this)
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                keyDispatcherState?.handleUpEvent(event)
                if (event.isTracking && !event.isCanceled) onDismissKeyboard?.invoke()
                return true
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 33) dismissKeyboard?.let { backDispatcher?.unregisterOnBackInvokedCallback(it) }
        backDispatcher = null
        dismissKeyboard = null
        activeConnection?.closeConnection()
        removeCallbacks(publish)
        removeCallbacks(showKeyboard)
        requestedKeyboard = true
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnWindowFocusChangeListener(keyboardWindowFocus)
        connectionGeneration++
        batchDepth = 0
        afterBatch.clear()
        pendingPublish = false
        super.onDetachedFromWindow()
    }
}
