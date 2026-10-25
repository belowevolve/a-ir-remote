package belowevolve.airremote

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier

@Composable
internal fun CompanionDialog(model: PcRemoteModel, dismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    fun pair(value: String) {
        try { model.pairCompanion(value); error = ""; code = ""; scanning = false; dismiss() }
        catch (e: IllegalArgumentException) { error = e.message.orEmpty(); scanning = false }
    }
    if (scanning) {
        CompanionScanner(onCode = ::pair, dismiss = { scanning = false })
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
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Код подключения") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (code.isNotBlank()) TextButton(onClick = { pair(code) }) { Text("Подключить") }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = if (model.hasCompanion) ({ TextButton(onClick = { model.forgetCompanion(); dismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Забыть ПК") } }) else null,
        confirmButton = { TextButton(onClick = dismiss) { Text("Готово") } },
    )
}
