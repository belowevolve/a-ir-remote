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
    var help by rememberSaveable { mutableStateOf(false) }
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
        Text("Звук по Wi-Fi · клавиатура по Bluetooth", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (state.muted) "Микрофон выключен" else state.message, style = MaterialTheme.typography.titleMedium)
                Text(state.format, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { state.peak }, modifier = Modifier.fillMaxWidth())
                Text(when {
                    !state.streaming -> "Уровень появится после подключения"
                    state.muted -> "На ПК передаётся тишина"
                    state.peak >= 0.98f -> "Перегрузка: отодвинь телефон от источника звука"
                    else -> "Уровень входного сигнала"
                }, style = MaterialTheme.typography.bodySmall)
                if (state.dropped > 0) Text("Пропущено пакетов: ${state.dropped}. Проверь качество Wi-Fi.")
            }
        }
        OutlinedTextField(host, { host = it }, label = { Text("IPv4-адрес ПК") }, placeholder = { Text("192.168.1.20") },
            enabled = !state.active, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it }, label = { Text("TCP-порт AndroidMic") }, enabled = !state.active,
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = natural, onClick = { natural = true }, enabled = !state.active, label = { Text("Естественный звук") })
            FilterChip(selected = !natural, onClick = { natural = false }, enabled = !state.active, label = { Text("Речь") })
        }
        Text(if (natural) "Без программного усиления. Необработанный вход, если его поддерживает телефон; иначе режим распознавания речи."
            else "Режим связи: обработка шума и эха зависит от телефона. Подходит для разговоров.", style = MaterialTheme.typography.bodySmall)
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
        Text("Передача продолжается при блокировке экрана. Остановить её можно здесь или в уведомлении.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { help = true }) { Text("Настроить Windows") }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("Подключение к Windows") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("1. Установи VB-CABLE и перезагрузи ПК. Он добавит виртуальный микрофон.")
            TextButton(onClick = { uri.openUri("https://vb-audio.com/Cable/") }) { Text("Скачать VB-CABLE") }
            Text("2. Запусти AndroidMic для Windows. Выбери TCP, выход CABLE Input и нажми Connect. Разреши доступ в брандмауэре для частной сети.")
            TextButton(onClick = { uri.openUri("https://github.com/teamclouday/AndroidMic/releases") }) { Text("Скачать AndroidMic") }
            Text("3. Телефон и ПК должны быть в одной локальной сети. Введи IP и порт из AndroidMic. Если указан 0.0.0.0, возьми IPv4 сетевого адаптера ПК из ipconfig.")
            Text("4. В Discord, OBS или другой программе выбери микрофон CABLE Output. Для естественного звука отключи шумоподавление в AndroidMic; выставь выход 48 кГц, если устройство поддерживает.")
            Text("Лучше Wi-Fi 5/6 ГГц, ПК можно подключить кабелем к тому же роутеру. Bluetooth-клавиатура работает независимо. Звук передаётся без шифрования: используй доверенную домашнюю сеть.")
        }
    }, confirmButton = { TextButton(onClick = { help = false }) { Text("Понятно") } })
}
