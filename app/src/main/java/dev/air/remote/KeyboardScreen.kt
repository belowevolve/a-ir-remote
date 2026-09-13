package dev.air.remote

import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun KeyboardScreen(model: RemoteModel) {
    Dialog(
        onDismissRequest = model::hideKeyboard,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
        val inputType = model.keyboardInputType.takeIf { it != 0 } ?: android.text.InputType.TYPE_CLASS_TEXT
        val imeOptions = model.keyboardImeOptions.takeIf { it and 0xff != 0 } ?: EditorInfo.IME_ACTION_DONE
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = model::hideKeyboard) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрыть клавиатуру")
                    }
                    Text("Клавиатура", fontSize = 24.sp)
                }
                Spacer(Modifier.height(32.dp))
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        RemoteEditText(context).apply {
                            background = null
                            setPadding(0, 0, 0, 0)
                            gravity = Gravity.TOP or Gravity.START
                            textSize = 30f
                            hint = "Введите текст"
                            setTextColor(textColor)
                            setHintTextColor(hintColor)
                            setSingleLine(true)
                            this.inputType = inputType
                            this.imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                            onValueChanged = model::editKeyboard
                            onBoundaryDelete = model::backspaceKeyboard
                            setOnEditorActionListener { _, _, event ->
                                if (event == null || event.action == android.view.KeyEvent.ACTION_DOWN) model.submitKeyboard()
                                true
                            }
                            applyValue(model.keyboardValue)
                            post {
                                requestFocus()
                                context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                            }
                        }
                    },
                    update = { editor ->
                        if (editor.inputType != inputType) editor.inputType = inputType
                        editor.imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        editor.applyValue(model.keyboardValue)
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = model::deleteKeyboardCharacter) {
                        Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Удалить символ на ТВ")
                    }
                    TextButton(onClick = model::submitKeyboard) { Text("Ввод") }
                }
            }
        }
    }
}
