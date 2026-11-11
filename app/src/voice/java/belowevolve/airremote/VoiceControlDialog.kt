package belowevolve.airremote

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun VoiceControlDialog(hasTv: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by VoiceControlService.status.collectAsState()
    var selected by remember { mutableStateOf(RemoteAssistantService.isSelected(context)) }
    var permissionError by remember { mutableStateOf(false) }
    val tvHost = context.getSharedPreferences("remote", android.content.Context.MODE_PRIVATE).getString("host", "").orEmpty()
    var airHome by remember(tvHost) { mutableStateOf(VoiceTvFeatures.airHome(context, tvHost)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) selected = RemoteAssistantService.isSelected(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            permissionError = false
            VoiceControlService.start(context)
        } else permissionError = true
    }
    val listening = state.active || VoiceControlService.enabled(context)
    val listeningAllowed = hasTv || listening
    val onListeningChanged: (Boolean) -> Unit = { enabled ->
        if (!enabled) VoiceControlService.stop(context)
        else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            permissionError = false
            VoiceControlService.start(context)
        }
        else permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }
    RemoteDialog(
        screen = Unit,
        onDismissRequest = onDismiss,
        title = { Text("Голосовое управление") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
                DialogActionGroup {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(listening, enabled = listeningAllowed, role = Role.Switch, onValueChange = onListeningChanged)
                        .padding(horizontal = RemoteLayout.Gap, vertical = RemoteLayout.SmallGap),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RemoteLayout.ExtraSmallGap)) {
                            Text("Слушать при блокировке", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (listeningAllowed) 1f else 0.38f))
                            Text(when {
                                !hasTv -> "Сначала подключите телевизор."
                                permissionError -> "Разрешите доступ к микрофону."
                                else -> state.message
                            }, style = MaterialTheme.typography.bodySmall,
                                color = if (permissionError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(listening, onCheckedChange = null, enabled = listeningAllowed)
                    }
                    DialogActionRow("Ассистент по умолчанию", selected = selected, highlightSelected = false) {
                        openSystemSettings(context, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    }
                    NotificationSettingsRow("local_voice", "Голосовое управление")
                }
                Column(verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                    Text("Скажите «Эйр», затем команду", style = MaterialTheme.typography.titleSmall)
                    VoiceCommandGroup("Пульт") {
                        VoiceCommandRow(Icons.Rounded.PowerSettingsNew, "ТВ", "Включить или выключить телевизор")
                        VoiceCommandRow(Icons.Rounded.SmartDisplay, "Ютуб", "Открыть YouTube")
                        VoiceCommandRow(Icons.Rounded.CheckCircleOutline, "ок · окей", "Нажать ОК")
                        VoiceCommandRow(Icons.AutoMirrored.Rounded.ArrowBack, "назад", "Нажать Назад")
                        VoiceCommandRow(Icons.Rounded.MicOff, "хватит слушать", "Выключить прослушивание")
                    }
                    VoiceCommandGroup("Воспроизведение") {
                        VoiceCommandRow(Icons.Rounded.Pause, "стоп · пауза", "Пауза")
                        VoiceCommandRow(Icons.Rounded.PlayArrow, "плей · продолжай · дальше", "Продолжить воспроизведение")
                        VoiceCommandRow(Icons.Rounded.SkipNext, "следующее · скип", "Следующее видео")
                        VoiceCommandRow(Icons.Rounded.SkipPrevious, "предыдущее · прошлое", "Предыдущее видео")
                        VoiceCommandRow(Icons.AutoMirrored.Rounded.VolumeUp, "Громче \"2\"", "Увеличить громкость на указанное число шагов")
                        VoiceCommandRow(Icons.AutoMirrored.Rounded.VolumeDown, "Тише \"3\"", "Уменьшить громкость на указанное число шагов")
                    }
                    DialogActionGroup {
                        VoiceCommandHeader("Air Home", Modifier
                            .clipToBounds()
                            .toggleable(airHome, enabled = hasTv, role = Role.Switch) {
                                airHome = it
                                VoiceTvFeatures.setAirHome(context, tvHost, it)
                            }
                            .semantics { stateDescription = if (airHome) "Установлен на этом ТВ" else "Не установлен на ТВ" }) {
                            Box(Modifier.size(width = 26.dp, height = 16.dp), contentAlignment = Alignment.Center) {
                                Switch(airHome, onCheckedChange = null, enabled = hasTv,
                                    modifier = Modifier.requiredSize(width = 52.dp, height = 32.dp).scale(0.5f))
                            }
                        }
                        if (!airHome) DialogActionRow("Гайд по установке") {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/belowevolve/a-ir-remote/blob/master/tv/README.md"))) }
                        }
                        else {
                            VoiceCommandRow(Icons.Rounded.Movie, "фильм", "Открыть film.mov")
                            VoiceCommandRow(Icons.Rounded.Computer, "комп · компьютер", "Последний HDMI")
                            VoiceCommandRow(ImageVector.vectorResource(R.drawable.ic_volume_settings), "Громкость \"45\"", "Точная громкость, от 0 до 100")
                            VoiceCommandRow(Icons.Rounded.FastForward, "Перемотай \"15 минут\"", "Перемотка вперёд")
                            VoiceCommandRow(Icons.Rounded.FastRewind, "Отмотай \"30 секунд\"", "Перемотка назад")
                            Spacer(Modifier.height(RemoteLayout.ExtraSmallGap))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

@Composable
private fun VoiceCommandGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    DialogActionGroup {
        VoiceCommandHeader(title)
        content()
        Spacer(Modifier.height(RemoteLayout.ExtraSmallGap))
    }
}

@Composable
private fun VoiceCommandHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(modifier)
        .padding(horizontal = RemoteLayout.Gap).padding(top = RemoteLayout.SmallGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
        Text(title, Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
        trailing?.invoke()
    }
}

@Composable
private fun VoiceCommandRow(icon: ImageVector, command: String, meaning: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = RemoteLayout.Gap, vertical = RemoteLayout.ExtraSmallGap).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
        Icon(icon, meaning, Modifier.size(RemoteLayout.InlineIconSize), tint = MaterialTheme.colorScheme.primary)
        Text(command, style = MaterialTheme.typography.bodyMedium)
    }
}
