package belowevolve.airremote

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.view.ViewConfiguration

@Composable
internal fun Trackpad(model: PcRemoteModel, modifier: Modifier = Modifier) {
    val doubleTapSlop = ViewConfiguration.get(LocalContext.current).scaledDoubleTapSlop.toFloat()
    Box(
        modifier = modifier.fillMaxWidth()
            .clip(KeyboardLayout.TrackpadShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = "Трекпад" }
            .pointerInput(model.connected) {
                var remainderX = 0f
                var remainderY = 0f
                var scrollRemainder = 0f
                val gestures = TrackpadGestures(
                    slop = viewConfiguration.touchSlop,
                    doubleTapSlop = doubleTapSlop,
                    doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis,
                    holdTimeout = viewConfiguration.longPressTimeoutMillis,
                    move = { dx, dy ->
                        remainderX += dx / density * model.sensitivity
                        remainderY += dy / density * model.sensitivity
                        val x = remainderX.toInt()
                        val y = remainderY.toInt()
                        if (x != 0 || y != 0) model.move(x, y)
                        remainderX -= x
                        remainderY -= y
                    },
                    scroll = { dy ->
                        scrollRemainder -= dy / 24.dp.toPx()
                        val steps = scrollRemainder.toInt()
                        if (steps != 0) model.move(0, 0, steps)
                        scrollRemainder -= steps
                    },
                    click = { model.click(right = it) },
                    button = { model.dragButton(pressed = it) },
                )
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        currentEvent.changes.forEach { it.consume() }
                        remainderX = 0f
                        remainderY = 0f
                        scrollRemainder = 0f
                        gestures.frame(down.uptimeMillis, currentEvent.changes.map {
                            TrackpadGestures.Contact(it.id.value, it.position.x, it.position.y, it.pressed)
                        })
                        var now = down.uptimeMillis
                        try {
                            while (gestures.active) {
                                val timeout = gestures.holdDelay(now)
                                val event = if (timeout != null) {
                                    withTimeoutOrNull(timeout) { awaitPointerEvent(PointerEventPass.Initial) }
                                } else awaitPointerEvent(PointerEventPass.Initial)
                                if (event == null) {
                                    gestures.hold()
                                    continue
                                }
                                now = event.changes.maxOf { it.uptimeMillis }
                                gestures.frame(now, event.changes.map {
                                    TrackpadGestures.Contact(it.id.value, it.position.x, it.position.y, it.pressed)
                                })
                                event.changes.forEach { it.consume() }
                            }
                        } finally {
                            if (gestures.active) gestures.cancel()
                        }
                    }
                } finally {
                    gestures.cancel()
                }
            },
    )
}
