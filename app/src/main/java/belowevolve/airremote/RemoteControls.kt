package belowevolve.airremote

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.onClick
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.sp

@Composable
internal fun ColorButton(
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
        modifier = modifier.height(TvLayout.ColorKeyHeight),
        shape = TvLayout.ColorKeyShape,
        color = color,
    ) {
        Box(contentAlignment = Alignment.Center) {}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RemoteButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surfaceContainer,
    tint: Color = Color(0xFFE0E5EC),
    onHold: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val click by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onHold)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "button press")
    val input = if (onHold == null) Modifier.combinedClickable(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); click() },
    ) else Modifier.semantics { onClick(label) { click(); true } }.pointerInput(Unit) {
        detectTapGestures(onPress = {
            coroutineScope {
                pressed = true
                var held = false
                val timer = launch {
                    delay(viewConfiguration.longPressTimeoutMillis)
                    held = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    hold?.invoke(true)
                }
                try {
                    if (tryAwaitRelease() && !held) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        click()
                    }
                } finally {
                    timer.cancel()
                    if (held) hold?.invoke(false)
                    pressed = false
                }
            }
        })
    }
    Surface(
        modifier = modifier.defaultMinSize(minWidth = RemoteLayout.ActionSize, minHeight = RemoteLayout.ActionSize)
            .scale(scale).clip(RemoteLayout.ActionShape).then(input),
        shape = RemoteLayout.ActionShape,
        color = background,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(RemoteLayout.ActionIconSize), tint = tint)
        }
    }
}

@Composable
internal fun WideButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(RemoteLayout.ActionSize),
        shape = RemoteLayout.ActionShape,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(RemoteLayout.InlineIconSize), tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(RemoteLayout.SmallGap))
            Text(label, fontSize = 13.sp, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
    }
}
