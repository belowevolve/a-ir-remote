package belowevolve.airremote

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
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

@Composable
internal fun Trackpad(model: PcRemoteModel, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth()
            .clip(KeyboardLayout.TrackpadShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = "Трекпад" }
            .pointerInput(model.connected) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    var travel = 0f
                    var fingers = 1
                    var remainderX = 0f
                    var remainderY = 0f
                    var scroll = 0f
                    var elapsed = 0L
                    var held = false
                    val origins = mutableMapOf(down.id to down.position)
                    try {
                        do {
                            // Own the gesture before the surrounding scroll container consumes it.
                            val event = if (!held && fingers == 1 && travel < viewConfiguration.touchSlop) {
                                withTimeoutOrNull((viewConfiguration.longPressTimeoutMillis - elapsed).coerceAtLeast(1L)) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                }
                            } else awaitPointerEvent(PointerEventPass.Initial)
                            if (event == null) {
                                held = true
                                model.dragButton(pressed = true)
                                continue
                            }
                            elapsed = event.changes.maxOf { it.uptimeMillis } - down.uptimeMillis
                            fingers = maxOf(fingers, event.changes.count { it.pressed })
                            event.changes.forEach { change ->
                                val origin = origins.getOrPut(change.id) { change.position }
                                travel = maxOf(travel, (change.position - origin).getDistance())
                            }
                            if (fingers > 1 && model.dragging) model.dragButton(pressed = false)
                            val moving = event.changes.filter { it.pressed && it.previousPressed }
                            if (moving.isNotEmpty()) {
                                val dx = moving.sumOf { it.positionChangeIgnoreConsumed().x.toDouble() }.toFloat() / moving.size
                                val dy = moving.sumOf { it.positionChangeIgnoreConsumed().y.toDouble() }.toFloat() / moving.size
                                if (moving.size >= 2) {
                                    if (model.dragging) model.dragButton(pressed = false)
                                    scroll += -dy / 24.dp.toPx()
                                    val steps = scroll.toInt()
                                    if (steps != 0) { model.move(0, 0, steps); scroll -= steps }
                                } else if (fingers == 1) {
                                    remainderX += (dx / density) * model.sensitivity
                                    remainderY += (dy / density) * model.sensitivity
                                    val x = remainderX.toInt()
                                    val y = remainderY.toInt()
                                    if ((x != 0) || (y != 0)) { model.move(x, y); remainderX -= x; remainderY -= y }
                                }
                            }
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        } while (true)
                        if ((travel < viewConfiguration.touchSlop) && (elapsed < viewConfiguration.longPressTimeoutMillis) && !held)
                            model.click(right = fingers > 1)
                    } finally {
                        if (model.dragging) model.dragButton(pressed = false)
                    }
                }
            },
    )
}
