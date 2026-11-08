package belowevolve.airremote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import belowevolve.shared.design.RemoteIcons
import kotlin.math.sqrt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun MicrophoneScreen(model: PcRemoteModel, beforeStart: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("microphone", Context.MODE_PRIVATE) }
    var deviceDialog by rememberSaveable { mutableStateOf<PcDialogPage?>(null) }
    var natural by rememberSaveable { mutableStateOf(!preferences.getBoolean("speech", true)) }
    var error by rememberSaveable { mutableStateOf("") }
    // Audio meters update independently, without rebuilding the screen's controls at 20 Hz.
    val controls = remember {
        MicrophoneService.status
            .map {
                it.copy(peak = 0f, waveform = emptyList(), sentFrames = 0, dropped = 0)
            }
            .distinctUntilChanged()
    }
    val state by controls.collectAsState(initial = remember { MicrophoneService.status.value })
    deviceDialog?.let { page ->
        PcDeviceDialog(
            model,
            kind = DeviceKind.MICROPHONE,
            initialPage = page,
            onDismiss = { deviceDialog = null },
        )
    }
    fun start() {
        if (!model.companionConnected) {
            deviceDialog = model.connectionPage()
            return
        }
        try {
            beforeStart()
            MicrophoneService.start(context, natural)
            preferences.edit {
                putBoolean("speech", !natural)
            }
            error = ""
        } catch (e: Exception) {
            error = e.message ?: "Не удалось запустить трансляцию"
        }
    }
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                error = "Нет доступа к микрофону"
            } else if (
                Build.VERSION.SDK_INT >= 37 &&
                    context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                        PackageManager.PERMISSION_GRANTED
            ) {
                error = "Нет доступа к устройствам поблизости"
            } else {
                start()
            }
        }
    ConnectionGate(model.companionConnected || state.active, "Подключить ПК", { deviceDialog = model.connectionPage() }) {
        PcPointerSurface(model, true, onConnect = { deviceDialog = model.connectionPage() }) { pointer ->
            Column(
                Modifier.fillMaxSize().padding(horizontal = RemoteLayout.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap),
            ) {
                AppHeader(
                    title = if (model.companionConnected) model.pcName else "Подключить ПК",
                    actionIcon =
                        if (model.companionConnected)
                            ImageVector.vectorResource(RemoteIcons.Tune)
                        else Icons.Rounded.Devices,
                    actionDescription =
                        if (model.companionConnected) "Настройки микрофона"
                        else "Подключить ПК",
                    onAction = {
                        deviceDialog = model.deviceDialogPage(DeviceKind.MICROPHONE)
                    },
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(RemoteLayout.TabHeight)) {
                    listOf("Речь", "Естественный звук").forEachIndexed { index, label ->
                        SegmentedButton(
                            modifier = Modifier.height(RemoteLayout.TabHeight),
                            selected = natural == (index == 1),
                            onClick = {
                                natural = index == 1
                                preferences.edit { putBoolean("speech", !natural) }
                            },
                            enabled = !state.active,
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            label = { Text(label) },
                        )
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Box(pointer.weight(1f).fillMaxWidth()) {
                    if (model.waveformEnabled) MicrophoneMonitor(Modifier.fillMaxSize())

                }
                Row(horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
                    if (state.streaming) {
                        MicrophoneButton(
                            muted = state.muted,
                            dynamic = model.iconMeterEnabled,
                            modifier = Modifier.weight(1f),
                            onClick = { MicrophoneService.mute(context) },
                        )
                    } else {
                        Button(
                            modifier = Modifier.weight(1f).height(RemoteLayout.ActionSize),
                            shape = RemoteLayout.ActionShape,
                            enabled = !state.active,
                            onClick = {
                                if (!model.companionConnected) {
                                    deviceDialog = model.connectionPage()
                                    return@Button
                                }
                                permissions.launch(
                                    buildList {
                                        add(Manifest.permission.RECORD_AUDIO)
                                        if (Build.VERSION.SDK_INT >= 33)
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        if (Build.VERSION.SDK_INT >= 37)
                                            add(Manifest.permission.ACCESS_LOCAL_NETWORK)
                                    }
                                        .toTypedArray()
                                )
                            },
                        ) {
                            Text(
                                if (state.active) "Подключение…"
                                else if (!model.companionConnected) "Подключить ПК"
                                else "Включить микрофон"
                            )
                        }
                    }
                    if (state.active) {
                        RemoteButton(
                            icon = Icons.Rounded.Stop,
                            label = "Остановить передачу",
                            modifier = Modifier.size(RemoteLayout.ActionSize),
                        ) {
                            MicrophoneService.stop(context)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MicrophoneMonitor(modifier: Modifier = Modifier) {
    val state by MicrophoneService.status.collectAsState()
    val color =
        if (state.muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier.fillMaxWidth().height(100.dp).semantics {
                stateDescription = if (state.muted) "Без звука" else "Уровень микрофона"
            }
        ) {
            drawLine(
                baseline,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                1.dp.toPx(),
            )
            val step = size.width / 64
            state.waveform.forEachIndexed { index, peak ->
                val x = (64 - state.waveform.size + index + 0.5f) * step
                val halfHeight = sqrt(peak.coerceIn(0f, 1f)) * size.height * 0.45f
                drawLine(
                    color,
                    Offset(x, size.height / 2 - halfHeight),
                    Offset(x, size.height / 2 + halfHeight),
                    strokeWidth = (step * 0.55f).coerceAtLeast(1.dp.toPx()),
                    cap = StrokeCap.Round,
                )
            }
        }

        Text(
            if (state.muted) "Без звука" else state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.sentFrames > 0) {
            val seconds = state.sentFrames / 100
            Text(
                "Отправлено ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} звука",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MicrophoneButton(
    muted: Boolean,
    dynamic: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val peak = if (dynamic) {
        val state by MicrophoneService.status.collectAsState()
        state.peak
    } else 0f
    val level = if (dynamic) {
        animateFloatAsState(
            targetValue = if (muted) 0f else sqrt(peak.coerceIn(0f, 1f)),
            animationSpec = tween(100),
            label = "microphone level",
        ).value
    } else if (muted) 0f else 1f
    Surface(
        onClick = onClick,
        modifier =
            modifier.height(RemoteLayout.ActionSize).semantics {
                stateDescription = if (muted) "Микрофон выключен" else "Микрофон включён"
            },
        shape = RemoteLayout.ActionShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                if (muted) "Включить микрофон" else "Выключить микрофон",
                Modifier.size(RemoteLayout.MicrophoneIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
            if (!muted)
                Icon(
                    Icons.Rounded.Mic,
                    null,
                    Modifier.size(RemoteLayout.MicrophoneIconSize).drawWithContent {
                        clipRect(top = size.height * (1f - level)) {
                            this@drawWithContent.drawContent()
                        }
                    },
                    tint =
                        if (peak >= 0.98f) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                )
        }
    }
}
