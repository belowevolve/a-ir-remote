package belowevolve.airremote

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
                    title = if (model.connected) model.tvName else "Подключить ТВ",
                    actionIcon = Icons.Rounded.Tune,
                ) { settings = true }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteButton(
                        icon = Icons.Rounded.PowerSettingsNew,
                        label = "Питание",
                        onHold = model::holdPower,
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
                    TvDpad(model, Modifier.size(diameter))
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
                    RemoteButton(Icons.Rounded.FastRewind, "Перемотка назад", Modifier.weight(1f), repeatOnHold = true) { model.key(89) }
                    RemoteButton(Icons.Rounded.PlayArrow, "Пауза / воспроизведение", Modifier.weight(1f)) { model.key(85) }
                    RemoteButton(Icons.Rounded.FastForward, "Перемотка вперёд", Modifier.weight(1f), repeatOnHold = true) { model.key(90) }
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
                        label = if (model.recording) "Стоп" else if (model.voiceRequested) "Говорить" else "Голос",
                        modifier = Modifier.weight(1f),
                        active = model.recording || model.voiceRequested,
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
            title = { Text(if (model.connected) "ТВ · ${model.tvName}" else "Подключить ТВ") },
            text = {
                if (model.connected) {
                    DialogActionGroup {
                        DialogActionRow("Входы / HDMI") {
                            model.key(178)
                            settings = false
                        }
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                    if (model.devices.isNotEmpty()) DialogActionGroup {
                        model.devices.forEach { (name, host) ->
                            DialogActionRow(name, selected = host == model.host, enabled = !model.busy) {
                                model.select(name, host)
                                settings = false
                            }
                        }
                    } else Text("Ищем телевизоры…", fontSize = 13.sp)
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("IP-адрес ТВ") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                if (model.connected) TextButton(onClick = { settings = false }) { Text("Готово") }
                else TextButton(
                    onClick = {
                        model.selectAddress(address)
                        settings = false
                    },
                    enabled = address.isNotBlank() && !model.busy,
                ) { Text("Подключить") }
            },
            dismissButton = {
                if (model.host.isNotBlank()) TextButton(
                    onClick = { model.forgetTv(); settings = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Забыть ТВ") }
                else TextButton(onClick = { settings = false }) { Text("Закрыть") }
            },
        )
    }

    if (model.pairing) {
        var pin by remember { mutableStateOf("") }
        val pinFocus = remember { FocusRequester() }
        RemoteDialog(
            onDismissRequest = { model.cancelPairing() },
            title = { Text("Код на телевизоре") },
            text = {
                LaunchedEffect(Unit) { pinFocus.requestFocus() }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { newValue ->
                        pin = newValue.filter { c ->
                            (c.isDigit()) || (c.lowercaseChar() in ('a'..'f'))
                        }.take(6).uppercase()
                    },
                    label = { Text("6 символов") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(pinFocus),
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
