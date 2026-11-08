package belowevolve.airremote

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import belowevolve.shared.design.RemoteIcons

@Composable
internal fun RemoteScreen(model: RemoteModel, voice: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        snapshotFlow { model.message }
            .collect { text ->
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
        Box(
            Modifier.fillMaxSize()
                .clickable(enabled = !model.connected, onClick = model::showDeviceDialog),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier.widthIn(max = TvLayout.MaxWidth)
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = RemoteLayout.ScreenPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap),
            ) {
                AppHeader(
                    title = if (model.connected) model.tvName else "Подключить ТВ",
                    actionIcon =
                        if (model.connected) ImageVector.vectorResource(RemoteIcons.Tune)
                        else Icons.Rounded.Devices,
                    actionDescription = if (model.connected) "Настройки ТВ" else "Подключить ТВ",
                ) {
                    model.showDeviceDialog()
                }

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
                        modifier = Modifier.weight(1f).height(RemoteLayout.ActionSize),
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
                    ) {
                        model.key(164)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap),
                ) {
                    ColorButton(RemoteColors.Red, Modifier.weight(1f)) {
                        model.key(android.view.KeyEvent.KEYCODE_PROG_RED)
                    }
                    ColorButton(RemoteColors.Green, Modifier.weight(1f)) {
                        model.key(android.view.KeyEvent.KEYCODE_PROG_GREEN)
                    }
                    ColorButton(RemoteColors.Yellow, Modifier.weight(1f)) {
                        model.key(android.view.KeyEvent.KEYCODE_PROG_YELLOW)
                    }
                    ColorButton(RemoteColors.Blue, Modifier.weight(1f)) {
                        model.key(android.view.KeyEvent.KEYCODE_PROG_BLUE)
                    }
                }

                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    val diameter = minOf(maxWidth, maxHeight, TvLayout.DpadMaxSize)
                    TvDpad(true, model::key, model::holdOk, Modifier.size(diameter))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RemoteButton(
                        icon = ImageVector.vectorResource(RemoteIcons.Back),
                        label = "Назад",
                    ) {
                        model.key(4)
                    }
                    RemoteButton(icon = Icons.Rounded.Home, label = "Домой") {
                        model.key(3)
                    }
                    RemoteButton(
                        icon = ImageVector.vectorResource(RemoteIcons.Settings),
                        label = "Настройки ТВ",
                    ) {
                        model.key(176)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap),
                ) {
                    RemoteButton(Icons.Rounded.SkipPrevious, "Предыдущий", Modifier.weight(1f)) {
                        model.key(88)
                    }
                    RemoteButton(
                        Icons.Rounded.FastRewind,
                        "Перемотка назад",
                        Modifier.weight(1f),
                        repeatOnHold = true,
                    ) {
                        model.key(89)
                    }
                    RemoteButton(
                        Icons.Rounded.PlayArrow,
                        "Пауза / воспроизведение",
                        Modifier.weight(1f),
                    ) {
                        model.key(85)
                    }
                    RemoteButton(
                        Icons.Rounded.FastForward,
                        "Перемотка вперёд",
                        Modifier.weight(1f),
                        repeatOnHold = true,
                    ) {
                        model.key(90)
                    }
                    RemoteButton(Icons.Rounded.SkipNext, "Следующий", Modifier.weight(1f)) {
                        model.key(87)
                    }
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
                        label =
                            if (model.recording) "Стоп"
                            else if (model.voiceRequested) "Говорить" else "Голос",
                        modifier = Modifier.weight(1f),
                        active = model.recording || model.voiceRequested,
                        onClick = { if (model.connected) voice() else model.showDeviceDialog() },
                    )
                }
            }
        }
    }

    if (model.keyboardVisible) KeyboardScreen(model)

    val state = model.deviceDialog
    if (state != TvDeviceDialogState.CLOSED) {
        val screen = if (state.expectsCode) TvDeviceDialogState.CONNECTING else state
        RemoteDialog(
            screen = screen,
            onDismissRequest = model::dismissDeviceDialog,
            title = { screen ->
                DeviceDialogTitle(
                    DeviceKind.TV,
                    if (screen == TvDeviceDialogState.SETTINGS) model.tvName else "Подключить ТВ",
                )
            },
            text = { screen ->
                if (screen == TvDeviceDialogState.SETTINGS) {
                    DialogActionGroup {
                        DialogToggleRow("Автоклавиатура", model.autoKeyboard, model::updateAutoKeyboard)
                        if (!model.connected && model.connectionNeedsAttention)
                            DialogActionRow("Подключиться", loading = model.connecting) { model.connect() }
                        DialogActionRow("Входы / HDMI", enabled = model.connected) {
                            model.key(android.view.KeyEvent.KEYCODE_TV_INPUT)
                            model.dismissDeviceDialog()
                        }
                    }
                } else {
                    DeviceConnectionList(
                        devices = model.devices,
                        loading = model.devices.isEmpty(),
                        label = { it.first },
                        connected = { model.connected && it.second == model.host },
                        connecting = { (screen.busy || model.connecting) && it.second == model.host },
                        enabled = !screen.busy,
                    ) { (name, host) -> model.select(name, host) }
                }
            },
            confirmButton = { screen ->
                TextButton(onClick = model::dismissDeviceDialog) {
                    Text(if (screen == TvDeviceDialogState.SETTINGS) "Готово" else "Закрыть")
                }
            },
            dismissButton = { screen ->
                if (screen == TvDeviceDialogState.SETTINGS)
                    TextButton(
                        onClick = model::forgetTv,
                        colors =
                            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Забыть ТВ") }
            },
        )
        if (state.expectsCode) TvPairingCodeDialog(model)
    }
}

@Composable
private fun TvPairingCodeDialog(model: RemoteModel) {
    var pin by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    RemoteDialog(
        screen = model.deviceDialog,
        onDismissRequest = model::dismissDeviceDialog,
        title = { DeviceDialogTitle(DeviceKind.TV, "Ввести код") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { value ->
                    pin = value.filter { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }.take(6).uppercase()
                },
                label = { Text("6 символов") },
                textStyle = MaterialTheme.typography.bodyLarge,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { state ->
            TextButton(onClick = { model.finishPairing(pin) }, enabled = pin.length == 6 && !state.busy) {
                Text(if (state.busy) "Подключаем…" else "Подключить")
            }
        },
        dismissButton = { TextButton(onClick = model::dismissDeviceDialog) { Text("Отмена") } },
    )
}
