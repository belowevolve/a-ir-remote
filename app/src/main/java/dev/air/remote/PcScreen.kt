package dev.air.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun RemoteModes(pc: PcRemoteModel, onPcActivated: () -> Unit, onModeChanged: (Boolean) -> Unit, tv: @Composable () -> Unit) {
    var pcMode by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(pcMode) {
        onModeChanged(pcMode)
        if (pcMode) { onPcActivated(); pc.activate() } else pc.deactivate()
        onDispose { pc.deactivate() }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).pointerInput(Unit) {
                    var distance = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { distance = 0f },
                        onDragEnd = { if (abs(distance) > 48.dp.toPx()) pcMode = distance < 0 },
                    ) { change, dx -> change.consume(); distance += dx }
                },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterChip(selected = !pcMode, onClick = { pcMode = false }, label = { Text("ТВ") }, modifier = Modifier.weight(1f))
                FilterChip(selected = pcMode, onClick = { pcMode = true }, label = { Text("ПК") }, modifier = Modifier.weight(1f))
            }
            Box(Modifier.weight(1f)) { if (pcMode) PcScreen(pc) else tv() }
        }
    }
}

@Composable
private fun PcScreen(model: PcRemoteModel) {
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
        title = { Text("Подключение к Windows") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.status)
                Text("В Windows: Bluetooth → Добавить устройство → выбери телефон.")
                TextButton(onClick = { launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Включить Bluetooth") }
                TextButton(enabled = model.registered, onClick = {
                    if (Build.VERSION.SDK_INT >= 31 && view.context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                    else launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                }) { Text("Сделать телефон видимым на 2 минуты") }
                if (launchError.isNotEmpty()) Text(launchError)
                Text("Язык Windows: Win + Space. RU/EN меняет только подписи.\nТрекпад: касание — клик, два пальца — прокрутка / правый клик, удержание и движение — перетаскивание.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.start(); model.refreshDevices() }) { Text("Обновить / повторить") }
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

@Composable
private fun Trackpad(model: PcRemoteModel, keyboardVisible: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth().height(if (keyboardVisible) 190.dp else 320.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = "Трекпад" }
            .pointerInput(model.connected) {
                if (!model.connected) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    var travel = 0f
                    var fingers = 1
                    var remainderX = 0f
                    var remainderY = 0f
                    var scroll = 0f
                    var elapsed = 0L
                    try {
                        do {
                            // Own the gesture before the surrounding scroll container consumes it.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            elapsed = event.changes.maxOf { it.uptimeMillis } - down.uptimeMillis
                            fingers = maxOf(fingers, event.changes.count { it.pressed })
                            val moving = event.changes.filter { it.pressed && it.previousPressed }
                            if (moving.isNotEmpty()) {
                                if (fingers == 1 && travel < viewConfiguration.touchSlop &&
                                    elapsed >= viewConfiguration.longPressTimeoutMillis && !model.dragging) {
                                    model.dragButton(true)
                                }
                                val dx = moving.sumOf { it.positionChangeIgnoreConsumed().x.toDouble() }.toFloat() / moving.size
                                val dy = moving.sumOf { it.positionChangeIgnoreConsumed().y.toDouble() }.toFloat() / moving.size
                                travel += abs(dx) + abs(dy)
                                if (moving.size >= 2) {
                                    if (model.dragging) model.dragButton(false)
                                    scroll += -dy / 24.dp.toPx()
                                    val steps = scroll.toInt()
                                    if (steps != 0) { model.move(0, 0, steps); scroll -= steps }
                                } else if (fingers == 1) {
                                    remainderX += dx / density * model.sensitivity
                                    remainderY += dy / density * model.sensitivity
                                    val x = remainderX.toInt()
                                    val y = remainderY.toInt()
                                    if (x != 0 || y != 0) { model.move(x, y); remainderX -= x; remainderY -= y }
                                }
                            }
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        if (travel < viewConfiguration.touchSlop && elapsed < viewConfiguration.longPressTimeoutMillis && !model.dragging)
                            model.click(right = fingers > 1)
                    } finally {
                        if (model.dragging) model.dragButton(false)
                    }
                }
            },
    )
}

@Composable
private fun PcKeyboard(model: PcRemoteModel, russian: Boolean) {
    val rows = listOf(
        listOf("Esc" to 41) + (1..12).map { "F$it" to (57 + it) } + listOf("Del" to 76),
        listOf("`" to 53) + "1234567890".mapIndexed { index, c -> c.toString() to (30 + index) } + listOf("-" to 45, "=" to 46, "⌫" to 42),
        listOf("Tab" to 43) + "qwertyuiop".map { it.toString() to (4 + (it - 'a')) } + listOf("[" to 47, "]" to 48, "\\" to 49),
        listOf("Caps" to 57) + "asdfghjkl".map { it.toString() to (4 + (it - 'a')) } + listOf(";" to 51, "'" to 52, "Enter" to 40),
        listOf("Shift" to -2) + "zxcvbnm".map { it.toString() to (4 + (it - 'a')) } + listOf("," to 54, "." to 55, "/" to 56, "Shift" to -2),
    )
    val latin = "`qwertyuiop[]asdfghjkl;'zxcvbnm,."
    val cyrillic = "ёйцукенгшщзхъфывапролджэячсмитьбю"
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { (label, code) ->
                        val index = if (label.length == 1) latin.indexOf(label) else -1
                        PcKey(if (russian && index >= 0) cyrillic[index].toString() else label,
                            Modifier.weight(if (label.length > 1) 1.4f else 1f), model.connected, code == -2 && model.modifiers and 2 != 0) {
                            if (code == -2) model.toggleModifier(2) else model.key(code)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("Ctrl" to 1, "Win" to 8, "Alt" to 4).forEach { (label, mask) ->
                    PcKey(label, Modifier.weight(1f), model.connected, model.modifiers and mask != 0) { model.toggleModifier(mask) }
                }
                PcKey("Space", Modifier.weight(2.5f), model.connected) { model.key(44) }
                listOf("Home" to 74, "End" to 77).forEach { (label, code) ->
                    PcKey(label, Modifier.weight(1f), model.connected) { model.key(code) }
                }
            }
            Column(Modifier.align(Alignment.End).width(132.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                PcKey("↑", Modifier.align(Alignment.CenterHorizontally).width(42.dp), model.connected) { model.key(82) }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf("←" to 80, "↓" to 81, "→" to 79).forEach { (label, code) ->
                        PcKey(label, Modifier.weight(1f), model.connected) { model.key(code) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PcKey(label: String, modifier: Modifier, enabled: Boolean, selected: Boolean = false, click: () -> Unit) {
    Surface(onClick = click, enabled = enabled, modifier = modifier.height(42.dp), shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
    ) { Box(contentAlignment = Alignment.Center) { Text(label, fontSize = if (label.length >= 3) 10.sp else 13.sp, maxLines = 1) } }
}
