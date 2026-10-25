package belowevolve.airremote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.core.content.edit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

@Composable
fun MicrophoneScreen(model: PcRemoteModel, beforeStart: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("microphone", Context.MODE_PRIVATE) }
    val companion = if (model.hasCompanion) CompanionEndpoint.load(context) else null
    var pairing by rememberSaveable { mutableStateOf(false) }
    var natural by rememberSaveable { mutableStateOf(!preferences.getBoolean("speech", true)) }
    var error by rememberSaveable { mutableStateOf("") }
    val state by MicrophoneService.status.collectAsState()
    if (pairing) CompanionDialog(model) { pairing = false }
    fun start() {
        if (companion == null) { pairing = true; return }
        try {
            beforeStart()
            MicrophoneService.start(context, natural)
            preferences.edit {
                putBoolean("speech", !natural)
            }
            error = ""
        } catch (e: Exception) { error = e.message ?: "Не удалось запустить трансляцию" }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "Нет доступа к микрофону"
        } else if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            error = "Нет доступа к устройствам поблизости"
        } else {
            start()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = RemoteLayout.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap),
    ) {
        AppHeader(
            title = companion?.host ?: "Микрофон",
            actionIcon = Icons.Rounded.Devices,
            actionDescription = "Подключить ПК",
            onAction = { pairing = true },
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
        if (!state.active && state.message !in listOf("Готов к подключению", "Трансляция остановлена")) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
            if (state.streaming) {
                MicrophoneButton(
                    muted = state.muted, peak = state.peak, modifier = Modifier.weight(1f),
                    onClick = { MicrophoneService.mute(context) },
                )
            } else {
                Button(
                    modifier = Modifier.weight(1f).height(RemoteLayout.ActionSize),
                    shape = RemoteLayout.ActionShape,
                    enabled = !state.active,
                    onClick = {
                        if (companion == null) { pairing = true; return@Button }
                        permissions.launch(buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        }.toTypedArray())
                    },
                ) { Text(if (state.active) "Подключение…" else if (companion == null) "Подключить ПК" else "Включить микрофон") }
            }
            if (state.active) {
                RemoteButton(
                    icon = Icons.Rounded.Stop,
                    label = "Остановить передачу",
                    modifier = Modifier.size(RemoteLayout.ActionSize),
                ) { MicrophoneService.stop(context) }
            }
        }
    }
}

@Composable
private fun MicrophoneButton(muted: Boolean, peak: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val level by animateFloatAsState(
        targetValue = if (muted) 0f else sqrt(peak.coerceIn(0f, 1f)),
        animationSpec = tween(100), label = "microphone level",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.height(RemoteLayout.ActionSize).semantics {
            stateDescription = if (muted) "Микрофон выключен" else "Микрофон включён"
        },
        shape = RemoteLayout.ActionShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                if (muted) "Включить микрофон" else "Выключить микрофон",
                Modifier.size(RemoteLayout.MicrophoneIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
            if (!muted) Icon(Icons.Rounded.Mic, null,
                Modifier.size(RemoteLayout.MicrophoneIconSize).drawWithContent {
                    clipRect(top = size.height * (1f - level)) { this@drawWithContent.drawContent() }
                },
                tint = if (peak >= 0.98f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}
