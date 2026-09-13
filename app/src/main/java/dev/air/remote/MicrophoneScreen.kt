package dev.air.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun MicrophoneScreen(beforeStart: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("microphone", Context.MODE_PRIVATE) }
    var host by rememberSaveable { mutableStateOf(preferences.getString("host", "").orEmpty()) }
    var port by rememberSaveable { mutableStateOf(preferences.getString("port", "54345").orEmpty()) }
    var natural by rememberSaveable { mutableStateOf(preferences.getBoolean("natural", true)) }
    var error by rememberSaveable { mutableStateOf("") }
    val state by MicrophoneService.status.collectAsState()
    val uri = LocalUriHandler.current
    fun start() {
        val number = port.toIntOrNull()
        val address = host.trim()
        if (!address.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) || address.split('.').any { it.toInt() !in 0..255 }) {
            error = "Укажи IPv4-адрес компьютера"; return
        }
        if (number == null || number !in 1..65535) { error = "Порт должен быть от 1 до 65535"; return }
        try {
            beforeStart()
            MicrophoneService.start(context, address, number, natural)
            preferences.edit { putString("host", address); putString("port", port); putBoolean("natural", natural) }
            error = ""
        } catch (e: Exception) { error = e.message ?: "Не удалось запустить трансляцию" }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            error = "Для трансляции разреши доступ к микрофону в настройках приложения"
        else if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED)
            error = "Разреши доступ к устройствам поблизости для подключения к ПК"
        else start()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Микрофон для ПК", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.muted) "Микрофон выключен" else state.message, style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { state.peak }, modifier = Modifier.fillMaxWidth())
                if (state.streaming && !state.muted && state.peak >= 0.98f)
                    Text("Перегрузка", color = MaterialTheme.colorScheme.error)
                if (state.dropped > 0) Text("Пропущено пакетов: ${state.dropped}")
            }
        }
        OutlinedTextField(host, { host = it }, label = { Text("IP-адрес ПК") }, placeholder = { Text("192.168.1.20") },
            enabled = !state.active, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it }, label = { Text("Порт") }, enabled = !state.active,
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = natural, onClick = { natural = true }, enabled = !state.active, label = { Text("Естественный звук") })
            FilterChip(selected = !natural, onClick = { natural = false }, enabled = !state.active, label = { Text("Речь") })
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(modifier = Modifier.weight(1f), onClick = {
                if (state.active) MicrophoneService.stop(context)
                else permissions.launch(buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }.toTypedArray())
            }) { Text(if (state.active) "Остановить" else "Подключить") }
            if (state.streaming) OutlinedButton(onClick = { MicrophoneService.mute(context) }) { Text(if (state.muted) "Включить звук" else "Без звука") }
        }
        TextButton(onClick = { uri.openUri("https://github.com/belowevolve/a-ir-remote/blob/master/README.md#настройка") }) {
            Text("Настройка")
        }
    }
}
