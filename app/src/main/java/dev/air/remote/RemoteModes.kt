package dev.air.remote

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun RemoteModes(pc: PcRemoteModel, onPcActivated: () -> Unit, onModeChanged: (Boolean) -> Unit, tv: @Composable () -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(if (MicrophoneService.status.value.active) 2 else 0) }
    val pcMode = mode != 0
    DisposableEffect(pcMode) {
        onModeChanged(pcMode)
        if (pcMode) { onPcActivated(); pc.activate() } else pc.deactivate()
        onDispose { pc.deactivate() }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).pointerInput(Unit) {
            var distance = 0f
            detectHorizontalDragGestures(
                onDragStart = { distance = 0f },
                onDragEnd = {
                    if (abs(distance) > 48.dp.toPx()) mode = Math.floorMod(mode + if (distance < 0) 1 else -1, 3)
                },
            ) { change, dx -> change.consume(); distance += dx }
        }) {
            ModeTabs(mode, onSelected = { mode = it })
            Box(Modifier.weight(1f).padding(vertical = RemoteLayout.SmallGap)) {
                when (mode) {
                    1 -> PcScreen(pc)
                    2 -> MicrophoneScreen(beforeStart = onPcActivated)
                    else -> tv()
                }
            }
        }
    }
}
