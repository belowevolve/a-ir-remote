package belowevolve.airremote

import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun PcTextEntry(model: PcRemoteModel, onDismiss: () -> Unit) {
    if (!model.nativeKeyboardSupported) return
    var session by remember { mutableIntStateOf(-1) }
    var editorView by remember { mutableStateOf<RemoteEditText?>(null) }
    DisposableEffect(model) {
        val opened = model.openNativeKeyboard()
        session = opened
        onDispose { model.leaveNativeKeyboard(opened) }
    }
    LaunchedEffect(model.nativeKeyboardInitializationReady, editorView, session) {
        if (model.nativeKeyboardInitializationReady && session >= 0) {
            val opened = session
            editorView?.let { editor ->
                editor.afterInputBatch {
                    model.finishNativeKeyboardInitialization(opened)?.let(editor::applyValue)
                }
            }
        }
    }
    LaunchedEffect(model.nativeKeyboardKeyAck, editorView, session) {
        if (model.nativeKeyboardKeyAck != null && session >= 0) {
            val opened = session
            editorView?.let { editor ->
                editor.afterInputBatch {
                    model.finishNativeKeyboardKey(opened)?.let(editor::applyValue)
                }
            }
        }
    }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    fun submit() {
        editorView?.let { editor ->
            editor.afterInputBatch {
                model.nativeKeyboardKey(session, "enter")
                editor.applyValue(model.nativeKeyboardValue)
            }
        }
    }
    fun clear() {
        editorView?.let { editor ->
            editor.afterInputBatch {
                model.nativeKeyboardKey(session, "clear")
                editor.applyValue(model.nativeKeyboardValue)
            }
        }
    }
    fun dismiss() {
        model.closeNativeKeyboard(session)
        onDismiss()
    }
    Dialog(
        onDismissRequest = ::dismiss,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CaptureDialogVolumeKeys()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .padding(
                        horizontal = RemoteLayout.ScreenPadding,
                        vertical = RemoteLayout.SmallGap,
                    )
            ) {
                if (
                    model.nativeKeyboardStatus == PcTextStatus.STOPPED &&
                        model.nativeKeyboardMessage.isNotEmpty()
                ) {
                    Text(
                        model.nativeKeyboardMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(RemoteLayout.SmallGap))
                }
                if (session >= 0)
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = { context ->
                            val opened = session
                            RemoteEditText(context).apply {
                                editorView = this
                                onDismissKeyboard = ::dismiss
                                isEnabled = model.nativeKeyboardStatus != PcTextStatus.CONNECTING
                                background = null
                                setPadding(0, 0, 0, 0)
                                gravity = Gravity.TOP or Gravity.START
                                textSize = 30f
                                hint = "Введите текст"
                                setTextColor(textColor)
                                setHintTextColor(hintColor)
                                inputType =
                                    InputType.TYPE_CLASS_TEXT or
                                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                                        InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                                forceImeAction = true
                                imeOptions =
                                    EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                                setHorizontallyScrolling(false)
                                onValueChanged = { model.editNativeKeyboard(opened, it) }
                                onBoundaryDelete = {
                                    model.nativeKeyboardKey(opened, "backspace", it)
                                }
                                setOnEditorActionListener { _, _, event ->
                                    if (
                                        event == null ||
                                            event.action == android.view.KeyEvent.ACTION_DOWN
                                    )
                                        submit()
                                    true
                                }
                                applyValue(model.nativeKeyboardValue)
                            }
                        },
                        update = { editor ->
                            val enable = model.nativeKeyboardStatus != PcTextStatus.CONNECTING
                            val wasDisabled = !editor.isEnabled
                            editor.isEnabled = enable
                            editor.applyValue(model.nativeKeyboardValue)
                            if (wasDisabled && enable) editor.showKeyboardWhenReady()
                        },
                    )
                else Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::dismiss) { Text("Закрыть") }
                    TextButton(
                        onClick = ::clear,
                        enabled = model.nativeKeyboardStatus == PcTextStatus.ACTIVE,
                    ) {
                        Text("Очистить")
                    }
                    Spacer(Modifier.weight(1f))
                    if (model.nativeKeyboardStatus == PcTextStatus.CONNECTING) {
                        CircularProgressIndicator(Modifier.size(RemoteLayout.InlineIconSize))
                    }
                    TextButton(
                        onClick = ::submit,
                        enabled = model.nativeKeyboardStatus == PcTextStatus.ACTIVE,
                    ) {
                        Text("Ввод")
                    }
                }
            }
        }
    }
}
