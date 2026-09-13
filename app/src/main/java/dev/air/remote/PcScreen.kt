package dev.air.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

@Composable
internal fun PcScreen(model: PcRemoteModel) {
    var devicesVisible by rememberSaveable { mutableStateOf(false) }
    var keyboardVisible by rememberSaveable { mutableStateOf(true) }
    var russian by rememberSaveable { mutableStateOf(false) }
    var launchError by remember { mutableStateOf("") }
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.start(); model.refreshDevices() }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (model.hasPermission()) { model.start(); devicesVisible = true } else model.permissionDenied()
    }
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous; model.releaseInputs() }
    }
    fun launch(intent: Intent) {
        try { bluetooth.launch(intent); launchError = "" }
        catch (_: android.content.ActivityNotFoundException) { launchError = "Открой настройки Bluetooth телефона вручную" }
        catch (_: SecurityException) { model.permissionDenied() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Компьютер", style = MaterialTheme.typography.headlineSmall)
                Text(model.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 31 && !model.hasPermission()) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                else { model.start(); devicesVisible = true }
            }) { Text(if (model.connected) "Устройства" else "Подключить") }
        }
        Trackpad(model, keyboardVisible)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { keyboardVisible = !keyboardVisible }) { Text("Клавиатура") }
            TextButton(onClick = { settingsVisible = true }) { Text("Настройки") }
            TextButton(onClick = { russian = !russian }) { Text(if (russian) "RU" else "EN") }
        }
        if (keyboardVisible) {
            PcKeyboard(model, russian)
        }
    }
    if (devicesVisible) AlertDialog(
        onDismissRequest = { devicesVisible = false },
        title = { Text("Устройства") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.status)
                TextButton(onClick = { launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Включить Bluetooth") }
                TextButton(enabled = model.registered, onClick = {
                    if (Build.VERSION.SDK_INT >= 31 && view.context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                    else launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                }) { Text("Видимость · 2 минуты") }
                if (launchError.isNotEmpty()) Text(launchError)
                TextButton(onClick = { model.start(); model.refreshDevices() }) { Text("Обновить") }
                model.devices.forEach { device ->
                    TextButton(enabled = model.registered && !model.connected, onClick = { model.connect(device) }) { Text(model.deviceLabel(device)) }
                }
                if (model.connected) TextButton(onClick = model::disconnect) { Text("Отключить компьютер") }
            }
        },
        confirmButton = { TextButton(onClick = { devicesVisible = false }) { Text("Готово") } },
    )
    if (settingsVisible) {
        AlertDialog(
            onDismissRequest = { settingsVisible = false },
            title = { Text("Тачпад") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Чувствительность: ${"%.1f".format(model.sensitivity)}×")
                    Slider(
                        value = model.sensitivity,
                        onValueChange = model::updateSensitivity,
                        valueRange = 0.6f..3.0f,
                        steps = 11,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { settingsVisible = false }) { Text("Готово") } },
        )
    }
}
