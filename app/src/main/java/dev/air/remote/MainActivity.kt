package dev.air.remote

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF101216)
private val Panel = Color(0xFF1C2027)
private val Muted = Color(0xFF929AA7)
private val Accent = Color(0xFFBCED91)
private val RedButton = Color(0xFFE86A70)
private val GreenButton = Color(0xFF63B978)
private val YellowButton = Color(0xFFF0C85A)
private val BlueButton = Color(0xFF62A9E8)

class MainActivity : ComponentActivity() {
    private val model: RemoteModel by viewModels()
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) model.startVoice() else model.message = "Для голоса нужен доступ к микрофону"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Ink,
                    surface = Panel,
                    onPrimary = Ink,
                ),
            ) {
                RemoteScreen(model) {
                    if (model.recording) model.stopVoice() else microphone.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.resume()
    }

    override fun onStop() {
        model.pause()
        super.onStop()
    }
}

@Composable
private fun RemoteScreen(model: RemoteModel, voice: () -> Unit) {
    var settings by remember { mutableStateOf(value = false) }
    var more by remember { mutableStateOf(value = false) }
    var irSettings by remember { mutableStateOf(value = false) }
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
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.tvName, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                }
                RemoteButton(
                    icon = Icons.Rounded.Tune,
                    label = "Настройки",
                    modifier = Modifier.size(48.dp),
                ) {
                    settings = true
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteButton(
                    icon = Icons.Rounded.PowerSettingsNew,
                    label = "Питание · ИК",
                    modifier = Modifier.size(68.dp),
                    background = Color(0xFF382326),
                    tint = Color(0xFFFF9696),
                ) {
                    if (model.irPattern.isBlank()) irSettings = true else model.power()
                }
                Surface(
                    onClick = model::launchYouTube,
                    enabled = model.connected,
                    modifier = Modifier
                        .weight(1f)
                        .height(68.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = Panel,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_youtube),
                            contentDescription = "YouTube",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("YouTube", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ColorButton(RedButton, Modifier.weight(1f)) { model.key(183) }
                ColorButton(GreenButton, Modifier.weight(1f)) { model.key(184) }
                ColorButton(YellowButton, Modifier.weight(1f)) { model.key(185) }
                ColorButton(BlueButton, Modifier.weight(1f)) { model.key(186) }
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val diameter = minOf(maxWidth, 252.dp)
                Box(
                    modifier = Modifier
                        .size(diameter)
                        .clip(CircleShape)
                        .background(Panel)
                        .border(1.dp, Color(0xFF2A3039), CircleShape),
                ) {
                    RemoteButton(
                        icon = Icons.Rounded.KeyboardArrowUp,
                        label = "Вверх",
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .size(72.dp),
                        background = Color.Transparent,
                    ) {
                        model.key(19)
                    }
                    RemoteButton(
                        icon = Icons.Rounded.KeyboardArrowDown,
                        label = "Вниз",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .size(72.dp),
                        background = Color.Transparent,
                    ) {
                        model.key(20)
                    }
                    RemoteButton(
                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        label = "Влево",
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 8.dp)
                            .size(72.dp),
                        background = Color.Transparent,
                    ) {
                        model.key(21)
                    }
                    RemoteButton(
                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        label = "Вправо",
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 8.dp)
                            .size(72.dp),
                        background = Color.Transparent,
                    ) {
                        model.key(22)
                    }
                    Surface(
                        onClick = { model.key(23) },
                        modifier = Modifier
                            .size(84.dp)
                            .align(Alignment.Center),
                        shape = CircleShape,
                        color = Accent,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "OK",
                                color = Ink,
                                fontSize = 23.sp,
                                fontWeight = FontWeight.SemiBold,
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
                RemoteButton(icon = Icons.Rounded.MoreHoriz, label = "Ещё") {
                    more = true
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(62.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Panel),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RemoteButton(
                        icon = Icons.Rounded.Remove,
                        label = "Тише",
                        modifier = Modifier.size(48.dp),
                    ) {
                        model.key(25)
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = "Громкость",
                        tint = Muted,
                        modifier = Modifier.size(19.dp),
                    )
                    RemoteButton(
                        icon = Icons.Rounded.Add,
                        label = "Громче",
                        modifier = Modifier.size(48.dp),
                    ) {
                        model.key(24)
                    }
                }
                RemoteButton(
                    icon = Icons.AutoMirrored.Rounded.VolumeOff,
                    label = "Без звука",
                    modifier = Modifier.size(62.dp),
                ) {
                    model.key(164)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

    if (model.keyboardVisible) KeyboardScreen(model)

    if (settings) {
        var address by remember { mutableStateOf(model.host) }
        AlertDialog(
            onDismissRequest = { settings = false },
            title = { Text("Телевизор") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(model.status, color = Muted)
                    Text("Выбери ТВ в одной сети с телефоном.", color = Muted)
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
                    )
                    TextButton(
                        onClick = {
                            settings = false
                            irSettings = true
                        },
                    ) {
                        Text("Настроить ИК-питание")
                    }
                    Text(
                        text = "ИК-передатчик: ${if (model.hasIr) "доступен" else "не найден"}",
                        color = Muted,
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
        AlertDialog(
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

    if (more) {
        AlertDialog(
            onDismissRequest = { more = false },
            title = { Text("Управление") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RemoteButton(icon = Icons.Rounded.FastRewind, label = "Перемотка назад") {
                            model.key(89)
                        }
                        RemoteButton(icon = Icons.Rounded.PlayArrow, label = "Пауза / воспроизведение") {
                            model.key(85)
                        }
                        RemoteButton(icon = Icons.Rounded.FastForward, label = "Перемотка вперёд") {
                            model.key(90)
                        }
                    }
                    TextButton(onClick = { model.key(178) }) { Text("Входы / HDMI") }
                    TextButton(onClick = { model.key(176) }) { Text("Настройки ТВ") }
                    TextButton(onClick = { model.key(166) }) { Text("Следующий канал") }
                    TextButton(onClick = { model.key(167) }) { Text("Предыдущий канал") }
                    TextButton(onClick = { model.key(223); more = false }) { Text("Выключить по Wi-Fi") }
                }
            },
            confirmButton = {
                TextButton(onClick = { more = false }) {
                    Text("Готово")
                }
            },
        )
    }

    if (irSettings) {
        AlertDialog(
            onDismissRequest = { irSettings = false },
            title = { Text("ИК-питание Haier") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Передатчик ${if (model.hasIr) "доступен" else "не найден"}. Сигнал для твоего Haier ещё нужно проверить.")
                    Text(
                        text = "Направь верхний торец телефона на ТВ и нажми «Проверить». Если телевизор выключится или включится — сохрани профиль.",
                        color = Muted,
                        fontSize = 13.sp,
                    )
                    Button(onClick = { model.testHaierPower() }) { Text("Проверить профиль Haier") }
                    TextButton(
                        onClick = {
                            model.saveIr(IrSignal.haierPower())
                            irSettings = false
                        },
                    ) {
                        Text("Сработало — сохранить")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { irSettings = false }) {
                    Text("Закрыть")
                }
            },
        )
    }
}

@Composable
private fun ColorButton(
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(13.dp),
        color = color,
    ) {
        Box(contentAlignment = Alignment.Center) {}
    }
}

@Composable
private fun RemoteButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    background: Color = Panel,
    tint: Color = Color(0xFFE0E5EC),
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier.defaultMinSize(minWidth = 62.dp, minHeight = 62.dp),
        shape = RoundedCornerShape(22.dp),
        color = background,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(28.dp), tint = tint)
        }
    }
}

@Composable
private fun WideButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(22.dp),
        color = if (active) Accent else Panel,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (active) Ink else Accent)
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp, color = if (active) Ink else Color.White)
        }
    }
}
