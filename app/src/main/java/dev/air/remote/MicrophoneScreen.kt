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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun MicrophoneScreen(beforeStart: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("microphone", Context.MODE_PRIVATE) }
    var host by rememberSaveable { mutableStateOf(preferences.getString("host", "").orEmpty()) }
    var port by rememberSaveable { mutableStateOf(preferences.getString("port", "54345").orEmpty()) }
    var natural by rememberSaveable { mutableStateOf(!preferences.getBoolean("speech", true)) }
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
            preferences.edit { putString("host", address); putString("port", port); putBoolean("speech", !natural) }
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight).padding(RemoteLayout.ScreenPadding), verticalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
            AppHeader("Микрофон", actionIcon = Icons.Rounded.Settings, onAction = {
                uri.openUri("https://github.com/belowevolve/a-ir-remote/blob/master/README.md#настройка")
            })
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(RemoteLayout.ScreenPadding), verticalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                FilterChip(selected = !natural, onClick = { natural = false; preferences.edit { putBoolean("speech", true) } }, enabled = !state.active, label = { Text("Речь") })
                FilterChip(selected = natural, onClick = { natural = true; preferences.edit { putBoolean("speech", false) } }, enabled = !state.active, label = { Text("Естественный звук") })
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
                Button(modifier = Modifier.weight(1f).height(RemoteLayout.ActionSize), shape = RemoteLayout.ActionShape, onClick = {
                    if (state.active) MicrophoneService.stop(context)
                    else permissions.launch(buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    }.toTypedArray())
                }) { Text(if (state.active) "Остановить" else "Подключить") }
                if (state.streaming) RemoteButton(
                    icon = if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    label = if (state.muted) "Включить микрофон" else "Выключить микрофон",
                    modifier = Modifier.size(RemoteLayout.ActionSize),
                    background = if (state.muted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                ) { MicrophoneService.mute(context) }
            }

        }
    }

}
