package dev.air.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp

@Composable
internal fun RemoteScreen(model: RemoteModel, voice: () -> Unit) {
    var settings by remember { mutableStateOf(value = false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        snapshotFlow { model.message }.collect { text ->
            if (text.isNotBlank()) {
                model.message = ""
                snackbar.showSnackbar(text)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = TvLayout.MaxWidth)
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = RemoteLayout.ScreenPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap),
            ) {
                AppHeader(
                    title = if (model.connected) model.tvName else "Не подключен",
                    actionIcon = Icons.Rounded.Tune,
                    onAction = { settings = true },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteButton(
                        icon = Icons.Rounded.PowerSettingsNew,
                        label = "Питание",
                        onHold = if (!model.hasIr) model::holdPower else null,
                        modifier = Modifier.size(RemoteLayout.ActionSize),
                        background = RemoteColors.PowerContainer,
                        tint = RemoteColors.Power,
                    ) {
                        model.power()
                    }
                    Surface(
                        onClick = model::launchYouTube,
                        enabled = model.connected,
                        modifier = Modifier
                            .weight(1f)
                            .height(RemoteLayout.ActionSize),
                        shape = RemoteLayout.ActionShape,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_youtube),
                                contentDescription = "YouTube",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(TvLayout.AppIconSize),
                            )
                            Spacer(Modifier.width(RemoteLayout.SmallGap))
                            Text("YouTube", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    RemoteButton(
                        icon = Icons.AutoMirrored.Rounded.VolumeOff,
                        label = "Без звука",
                        modifier = Modifier.size(RemoteLayout.ActionSize),
                    ) { model.key(164) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap),
                ) {
                    ColorButton(RemoteColors.Red, Modifier.weight(1f)) { model.key(183) }
                    ColorButton(RemoteColors.Green, Modifier.weight(1f)) { model.key(184) }
                    ColorButton(RemoteColors.Yellow, Modifier.weight(1f)) { model.key(185) }
                    ColorButton(RemoteColors.Blue, Modifier.weight(1f)) { model.key(186) }
                }

                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    val diameter = minOf(maxWidth, maxHeight, TvLayout.DpadMaxSize)
                    Box(
                        modifier = Modifier
                            .size(diameter)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(TvLayout.DpadBorder, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    ) {
                        RemoteButton(
                            icon = Icons.Rounded.KeyboardArrowUp,
                            label = "Вверх",
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = TvLayout.DpadInset)
                                .size(RemoteLayout.ActionSize),
                            background = Color.Transparent,
                        ) {
                            model.key(19)
                        }
                        RemoteButton(
                            icon = Icons.Rounded.KeyboardArrowDown,
                            label = "Вниз",
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = TvLayout.DpadInset)
                                .size(RemoteLayout.ActionSize),
                            background = Color.Transparent,
                        ) {
                            model.key(20)
                        }
                        RemoteButton(
                            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            label = "Влево",
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = TvLayout.DpadInset)
                                .size(RemoteLayout.ActionSize),
                            background = Color.Transparent,
                        ) {
                            model.key(21)
                        }
                        RemoteButton(
                            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            label = "Вправо",
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = TvLayout.DpadInset)
                                .size(RemoteLayout.ActionSize),
                            background = Color.Transparent,
                        ) {
                            model.key(22)
                        }
                        Surface(
                            modifier = Modifier
                                .size(TvLayout.ConfirmSize)
                                .align(Alignment.Center)
                                .semantics { onClick("OK") { model.key(23); true } }
                                .pointerInput(model.connected) {
                                    detectTapGestures(onPress = {
                                        coroutineScope {
                                            var held = false
                                            val timer = launch {
                                                delay(viewConfiguration.longPressTimeoutMillis)
                                                held = true
                                                model.holdOk(true)
                                            }
                                            try {
                                                val released = tryAwaitRelease()
                                                if (released && !held) model.key(23)
                                            } finally {
                                                timer.cancel()
                                                if (held) model.holdOk(false)
                                            }
                                        }
                                    })
                                },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "OK",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RemoteButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Назад") {
                        model.key(4)
                    }
                    RemoteButton(icon = Icons.Rounded.Home, label = "Домой") {
                        model.key(3)
                    }
                    RemoteButton(icon = Icons.Rounded.Settings, label = "Настройки ТВ") {
                        model.key(176)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap),
                ) {
                    RemoteButton(Icons.Rounded.SkipPrevious, "Предыдущий", Modifier.weight(1f)) { model.key(88) }
                    RemoteButton(Icons.Rounded.FastRewind, "Перемотка назад", Modifier.weight(1f)) { model.key(89) }
                    RemoteButton(Icons.Rounded.PlayArrow, "Пауза / воспроизведение", Modifier.weight(1f)) { model.key(85) }
                    RemoteButton(Icons.Rounded.FastForward, "Перемотка вперёд", Modifier.weight(1f)) { model.key(90) }
                    RemoteButton(Icons.Rounded.SkipNext, "Следующий", Modifier.weight(1f)) { model.key(87) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
                    WideButton(
                        icon = Icons.Rounded.Keyboard,
                        label = "Клавиатура",
                        modifier = Modifier.weight(1f),
                        onClick = model::showKeyboard,
                    )
                    WideButton(
                        icon = if (model.recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        label = if (model.recording) "Стоп" else "Голос",
                        modifier = Modifier.weight(1f),
                        active = model.recording,
                        onClick = voice,
                    )
                }
            }
        }
    }

    if (model.keyboardVisible) KeyboardScreen(model)

    if (settings) {
        var address by remember { mutableStateOf(model.host) }
        RemoteDialog(
            onDismissRequest = { settings = false },
            title = { Text("Телевизор") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                    Text(model.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    model.devices.forEach { (name, host) ->
                        TextButton(
                            onClick = {
                                model.select(name, host)
                                settings = false
                            },
                            enabled = !model.busy,
                        ) {
                            Text("$name\n$host")
                        }
                    }
                    if (model.devices.isEmpty()) {
                        Text("Ищем телевизоры…", fontSize = 13.sp)
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("IP-адрес ТВ") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { model.key(178) }) { Text("Входы / HDMI") }
                    TextButton(onClick = { model.key(166) }) { Text("Следующий канал") }
                    TextButton(onClick = { model.key(167) }) { Text("Предыдущий канал") }
                    TextButton(onClick = { model.key(223) }) { Text("Выключить по Wi-Fi") }
                    Text(
                        text = "ИК-передатчик: ${if (model.hasIr) "доступен" else "не найден"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.select("Haier S2 Pro", address)
                        settings = false
                    },
                    enabled = address.isNotBlank() && !model.busy,
                ) {
                    Text("Сопряжение")
                }
            },
            dismissButton = {
                TextButton(onClick = { settings = false }) {
                    Text("Закрыть")
                }
            },
        )
    }

    if (model.pairing) {
        var pin by remember { mutableStateOf("") }
        RemoteDialog(
            onDismissRequest = { model.cancelPairing() },
            title = { Text("Код на телевизоре") },
            text = {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { newValue ->
                        pin = newValue.filter { c ->
                            (c.isDigit()) || (c.lowercaseChar() in ('a'..'f'))
                        }.take(6).uppercase()
                    },
                    label = { Text("6 символов") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { model.finishPairing(pin) },
                    enabled = (pin.length == 6) && (!model.busy),
                ) {
                    Text(if (model.busy) "Подключаем…" else "Подключить")
                }
            },
        )
    }

}
