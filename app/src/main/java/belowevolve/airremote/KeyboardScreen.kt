package belowevolve.airremote

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
fun KeyboardScreen(model: RemoteModel) {
    Dialog(
        onDismissRequest = model::hideKeyboard,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CaptureDialogVolumeKeys()
        var editorView by remember { mutableStateOf<RemoteEditText?>(null) }
        val submit: () -> Unit = { editorView?.afterInputBatch(model::submitKeyboard) }
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
        val originalInputType = model.keyboardInputType.takeIf { it != 0 } ?: android.text.InputType.TYPE_CLASS_TEXT
        val inputType = if (originalInputType and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_TEXT)
            originalInputType or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else originalInputType
        val imeOptions = model.keyboardImeOptions.takeIf { (it and 0xff) != 0 } ?: EditorInfo.IME_ACTION_DONE
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = RemoteLayout.ScreenPadding, vertical = RemoteLayout.SmallGap)) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        RemoteEditText(context).apply {
                            editorView = this
                            onDismissKeyboard = model::hideKeyboard
                            background = null
                            setPadding(0, 0, 0, 0)
                            gravity = Gravity.TOP or Gravity.START
                            textSize = 30f
                            hint = "Введите текст"
                            setTextColor(textColor)
                            setHintTextColor(hintColor)
                            forceImeAction = true
                            this.inputType = inputType
                            maxLines = Int.MAX_VALUE
                            setHorizontallyScrolling(false)
                            this.imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                            onValueChanged = model::editKeyboard
                            onBoundaryDelete = model::backspaceKeyboard
                            setOnEditorActionListener { _, _, event ->
                                if (event == null || event.action == android.view.KeyEvent.ACTION_DOWN) submit()
                                true
                            }
                            applyValue(model.keyboardValue)
                        }
                    }
                ) { editor ->
                    if (editor.inputType != inputType) editor.inputType = inputType
                    editor.maxLines = Int.MAX_VALUE
                    editor.setHorizontallyScrolling(false)
                    editor.imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    editor.applyValue(model.keyboardValue)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = model::hideKeyboard) { Text("Закрыть") }
                    TextButton(onClick = { editorView?.let { editor -> editor.afterInputBatch {
                        model.editKeyboard(androidx.compose.ui.text.input.TextFieldValue())
                        editor.applyValue(model.keyboardValue)
                    } } }) { Text("Очистить") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = submit) { Text("Ввод") }
                }
            }
        }
    }
}
