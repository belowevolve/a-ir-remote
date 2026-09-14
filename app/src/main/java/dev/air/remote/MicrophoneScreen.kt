package dev.air.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
        Column(Modifier.fillMaxSize().heightIn(min = viewportHeight)
            .padding(horizontal = RemoteLayout.ScreenPadding).padding(bottom = RemoteLayout.SmallGap),
            verticalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
            AppHeader(
                title = when {
                    state.streaming -> host
                    state.active -> "Подключение…"
                    else -> "Не подключен"
                },
                actionIcon = Icons.AutoMirrored.Outlined.HelpOutline,
                actionDescription = "Как подключить микрофон",
                onAction = {
                uri.openUri("https://github.com/belowevolve/a-ir-remote/blob/master/README.md#настройка")
            })
            OutlinedTextField(host, { host = it }, label = { Text("IP-адрес ПК") }, placeholder = { Text("192.168.1.20") },
                enabled = !state.active, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it }, label = { Text("Порт") }, enabled = !state.active,
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                FilterChip(selected = !natural, onClick = { natural = false; preferences.edit { putBoolean("speech", true) } }, enabled = !state.active, label = { Text("Речь") })
                FilterChip(selected = natural, onClick = { natural = true; preferences.edit { putBoolean("speech", false) } }, enabled = !state.active, label = { Text("Естественный звук") })
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (!state.active && state.message !in listOf("Готов к подключению", "Трансляция остановлена"))
                Text(state.message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
                if (state.streaming) MicrophoneButton(
                    muted = state.muted, peak = state.peak, modifier = Modifier.weight(1f),
                    onClick = { MicrophoneService.mute(context) },
                ) else Button(modifier = Modifier.weight(1f).height(RemoteLayout.ActionSize),
                    shape = RemoteLayout.ActionShape, enabled = !state.active, onClick = {
                    permissions.launch(buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    }.toTypedArray())
                }) { Text(if (state.active) "Подключение…" else "Подключить") }
                if (state.active) RemoteButton(
                    icon = Icons.Rounded.Stop,
                    label = "Остановить передачу",
                    modifier = Modifier.size(RemoteLayout.ActionSize),
                ) { MicrophoneService.stop(context) }
            }

        }
    }

}

@Composable
private fun MicrophoneButton(muted: Boolean, peak: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(RemoteLayout.ActionSize).semantics {
            stateDescription = if (muted) "Микрофон выключен" else "Микрофон включён"
        },
        shape = RemoteLayout.ActionShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(horizontal = RemoteLayout.ScreenPadding),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                if (muted) "Включить микрофон" else "Выключить микрофон",
                Modifier.size(RemoteLayout.ActionIconSize))
            Spacer(Modifier.height(RemoteLayout.SmallGap))
            LinearProgressIndicator(
                progress = { if (muted) 0f else peak.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(RemoteLayout.MeterHeight),
                color = if (!muted && peak >= 0.98f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                drawStopIndicator = {},
            )
        }
    }
}
