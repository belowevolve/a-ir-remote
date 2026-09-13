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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView

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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val keyHeight = KeyboardLayout.keyHeight(maxHeight)
        val trackpadHeight = KeyboardLayout.trackpadHeight(maxHeight, keyHeight, keyboardVisible)
        val toolbar: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { russian = !russian }, modifier = Modifier.size(KeyboardLayout.ToolbarSize)) {
                    Icon(Icons.Rounded.Language, if (russian) "Русская раскладка" else "English")
                }
                IconButton(onClick = { settingsVisible = true }, modifier = Modifier.size(KeyboardLayout.ToolbarSize)) {
                    Icon(Icons.Rounded.Tune, "Настройки")
                }
                IconButton(onClick = { keyboardVisible = !keyboardVisible }, modifier = Modifier.size(KeyboardLayout.ToolbarSize)) {
                    Icon(if (keyboardVisible) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard,
                        if (keyboardVisible) "Скрыть клавиатуру" else "Показать клавиатуру")
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = RemoteLayout.SmallGap),
            verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap),
        ) {
            Box(Modifier.padding(horizontal = RemoteLayout.ScreenPadding)) {
                AppHeader(if (model.connected) model.deviceName else "Не подключен", onAction = {
                    if (Build.VERSION.SDK_INT >= 31 && !model.hasPermission()) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                    else { model.start(); devicesVisible = true }
                }, actionIcon = Icons.Rounded.Devices,
                    actionDescription = if (model.connected) "Устройства" else "Подключить")
            }
            Box(Modifier.padding(horizontal = RemoteLayout.ScreenPadding)) {
                Trackpad(model, Modifier.height(trackpadHeight))
            }
            if (keyboardVisible) {
                PcKeyboard(model, russian, keyHeight, toolbar)
            } else {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { toolbar() }
            }
        }
    }
    if (devicesVisible) RemoteDialog(
        onDismissRequest = { devicesVisible = false },
        title = { Text("Устройства") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                Text(model.status)
                if (!model.registered) TextButton(onClick = { launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Включить Bluetooth") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = model.registered, onClick = {
                        if (Build.VERSION.SDK_INT >= 31 && view.context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                        else launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                    }) { Text("Видимость · 2 минуты") }
                    TextButton(onClick = { model.start(); model.refreshDevices() }) { Text("Обновить") }
                }
                if (launchError.isNotEmpty()) Text(launchError)
                Column {
                    model.devices.forEach { device ->
                        TextButton(enabled = model.registered && !model.connected, onClick = { model.connect(device); devicesVisible = false }) { Text(model.deviceLabel(device)) }
                    }
                }
                if (model.connected) TextButton(onClick = model::disconnect) { Text("Отключить компьютер") }
            }
        },
        confirmButton = { TextButton(onClick = { devicesVisible = false }) { Text("Готово") } },
    )
    if (settingsVisible) {
        RemoteDialog(
            onDismissRequest = { settingsVisible = false },
            title = { Text("Настройки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                    Text("Тачпад", style = MaterialTheme.typography.titleSmall)
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
