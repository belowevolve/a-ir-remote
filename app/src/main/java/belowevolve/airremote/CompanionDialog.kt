package belowevolve.airremote

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@Composable
internal fun CompanionDialog(model: PcRemoteModel, dismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(!model.hasCompanion) }
    var manual by rememberSaveable { mutableStateOf(false) }
    fun pair(value: String) {
        try { model.pairCompanion(value); error = ""; code = ""; scanning = false; dismiss() }
        catch (e: IllegalArgumentException) { error = e.message.orEmpty(); code = value; scanning = false; manual = true }
    }
    if (manual) {
        val focus = remember { FocusRequester() }
        RemoteDialog(
            onDismissRequest = dismiss,
            title = { Text("Код подключения") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                    OutlinedTextField(value = code, onValueChange = { code = it; error = "" },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                        label = { Text("Код") }, isError = error.isNotEmpty())
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } },
            confirmButton = { TextButton(onClick = { pair(code) }, enabled = code.isNotBlank()) { Text("Подключить") } },
        )
        return
    }
    if (scanning) {
        CompanionScanner(onCode = ::pair, onManualCode = { scanning = false; manual = true }, dismiss = dismiss)
        return
    }
    RemoteDialog(
        onDismissRequest = dismiss,
        title = { Text(model.companionHost?.let { "ПК · $it" } ?: "Подключить ПК") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                model.companionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                FilledTonalButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (model.hasCompanion && !model.companionConnected && model.companionError == null) "Подключение…" else if (model.hasCompanion) "Подключить другой ПК" else "Сканировать QR")
                }

            }
        },
        dismissButton = if (model.hasCompanion) ({ TextButton(onClick = { model.forgetCompanion(); dismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Забыть ПК") } }) else null,
        confirmButton = { TextButton(onClick = dismiss) { Text("Готово") } },
    )
}
