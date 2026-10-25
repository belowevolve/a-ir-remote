package belowevolve.airremote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dialpad
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView

@Composable
internal fun PcScreen(model: PcRemoteModel, landscape: Boolean = false) {
    var devicesVisible by rememberSaveable { mutableStateOf(false) }
    var keyboardVisible by rememberSaveable { mutableStateOf(true) }
    var numpad by rememberSaveable { mutableStateOf(false) }
    val russian = model.russianLayout
    var launchError by remember { mutableStateOf("") }
    var companionVisible by rememberSaveable { mutableStateOf(false) }
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
    PcPointerSurface(model) { trackpadArea ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fullscreenKeyboard = landscape && keyboardVisible
            val keyHeight = if (fullscreenKeyboard) (maxHeight - KeyboardLayout.RowGap * (KeyboardLayout.ROW_COUNT - 1)) / KeyboardLayout.ROW_COUNT else KeyboardLayout.keyHeight(maxHeight)
            val toolbarSize = minOf(KeyboardLayout.ToolbarSize, keyHeight)
            val toolbar: @Composable () -> Unit = {
                Column(Modifier.width(KeyboardLayout.ToolbarSize * 4), verticalArrangement = Arrangement.spacedBy(KeyboardLayout.RowGap)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KeyboardLayout.KeyGap)) {
                        listOf(false, true).forEach { right ->
                            Surface(
                                onClick = { model.click(right) },
                                enabled = model.connected,
                                modifier = Modifier.weight(1f).height(keyHeight),
                                shape = KeyboardLayout.KeyShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painterResource(if (right) R.drawable.ic_mouse_right else R.drawable.ic_mouse_left),
                                        if (right) "Правая кнопка мыши" else "Левая кнопка мыши",
                                        Modifier.size(RemoteLayout.ActionIconSize),
                                    )
                                }
                            }
                        }
                    }
                    Row(Modifier.height(keyHeight), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(toolbarSize).clip(CircleShape)
                                .combinedClickable(
                                    role = Role.Button,
                                    onClickLabel = "Сменить язык ПК и подписи клавиш",
                                    onClick = model::switchLanguage,
                                    onDoubleClick = model::toggleKeyboardLayout,
                                )
                                .semantics {
                                    customActions = listOf(CustomAccessibilityAction("Сменить только подписи клавиш") {
                                        model.toggleKeyboardLayout()
                                        true
                                    })
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Language, if (russian) "Язык: русский" else "Язык: английский")
                        }
                        IconToggleButton(
                            checked = numpad,
                            onCheckedChange = { numpad = it; keyboardVisible = true },
                            modifier = Modifier.size(toolbarSize),
                        ) {
                            Icon(if (numpad) Icons.Rounded.Keyboard else Icons.Rounded.Dialpad,
                                if (numpad) "Показать буквенную клавиатуру" else "Показать нумпад и дополнительные клавиши")
                        }
                        IconButton(onClick = { keyboardVisible = !keyboardVisible }, modifier = Modifier.size(toolbarSize)) {
                            Icon(if (keyboardVisible) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard,
                                if (keyboardVisible) "Скрыть клавиатуру" else "Показать клавиатуру")
                        }
                        IconButton(onClick = { settingsVisible = true }, modifier = Modifier.size(toolbarSize)) {
                            Icon(Icons.Rounded.Tune, "Настройки")
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap),
            ) {
                if (!fullscreenKeyboard) Box(Modifier.padding(horizontal = RemoteLayout.ScreenPadding)) {
                    AppHeader(if (model.connected) model.deviceName else "Подключить ПК",
                        onAction = {
                        devicesVisible = true
                        if (model.hasPermission()) model.start()
                    }, actionIcon = Icons.Rounded.Devices,
                        actionDescription = if (model.connected) "Устройства" else "Подключить")
                }
                if (!fullscreenKeyboard) Box(Modifier.padding(horizontal = RemoteLayout.ScreenPadding).weight(1f)) {
                    Trackpad(trackpadArea.fillMaxSize())
                }
                if (keyboardVisible) {
                    PcKeyboard(model, russian, keyHeight, numpad, landscape = fullscreenKeyboard, toolbar = toolbar)
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { toolbar() }
                }
            }
        }
    }
    if (devicesVisible) RemoteDialog(
        onDismissRequest = { devicesVisible = false },
        title = { Text(model.selectedDeviceName?.let { "ПК · $it" } ?: "Подключить ПК") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                FilledTonalButton(onClick = { devicesVisible = false; companionVisible = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (model.companionConnected) "Компаньон" else if (model.hasCompanion && model.companionError == null) "Компаньон · подключение…" else "Подключить компаньон")
                }
                HorizontalDivider()
                Text("Bluetooth · клавиатура и мышь", style = MaterialTheme.typography.titleSmall)
                if (!model.hasPermission()) TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 31) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                }) { Text("Разрешить Bluetooth") }
                if (model.connectionError.isNotBlank()) Text(model.connectionError, color = MaterialTheme.colorScheme.error)
                if (model.bluetoothState == BluetoothAdapter.STATE_OFF) TextButton(onClick = { launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Включить Bluetooth") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                    FilledTonalButton(modifier = Modifier.weight(1.6f), contentPadding = PaddingValues(horizontal = RemoteLayout.Gap), enabled = model.registered, onClick = {
                        if (Build.VERSION.SDK_INT >= 31 && view.context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                        else { model.preparePairing(); launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)) }
                    }) { Text("Видимость · 2 мин") }
                    OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = RemoteLayout.Gap), onClick = { model.start(); model.refreshDevices() }) { Text("Обновить") }
                }
                if (launchError.isNotEmpty()) Text(launchError)
                DialogActionGroup {
                    model.devices.forEach { device ->
                        DialogActionRow(model.deviceLabel(device), selected = model.isSelectedDevice(device), loading = model.isConnectingDevice(device), enabled = model.registered) {
                            model.connect(device)
                        }
                    }
                }
            }
        },
        dismissButton = if (model.hasSelectedDevice) ({ TextButton(onClick = { model.forgetPc(); if (!model.hasSelectedDevice) devicesVisible = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Забыть ПК") } }) else null,
        confirmButton = { TextButton(onClick = { devicesVisible = false }) { Text("Готово") } },
    )
    if (companionVisible) CompanionDialog(model) { companionVisible = false }
    if (settingsVisible) {
        RemoteDialog(
            onDismissRequest = { settingsVisible = false },
            title = { Text("Настройки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                    if (landscape) FilledTonalButton(onClick = { settingsVisible = false; devicesVisible = true }, modifier = Modifier.fillMaxWidth()) { Text("Подключить ПК") }
                    Text("Тачпад", style = MaterialTheme.typography.titleSmall)
                    Text("Чувствительность: ${"%.1f".format(model.sensitivity)}×")
                    Slider(
                        value = model.sensitivity,
                        onValueChange = model::updateSensitivity,
                        valueRange = PcPointerSensitivity.Range,
                        steps = 36,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { settingsVisible = false }) { Text("Готово") } },
        )
    }
}
