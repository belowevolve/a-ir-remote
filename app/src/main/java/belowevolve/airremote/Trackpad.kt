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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PcPointerSurface(model: PcRemoteModel, trackpadVisible: Boolean, content: @Composable (Modifier) -> Unit) {
    var trackpadBounds by remember { mutableStateOf(Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    var releaseJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(model, trackpadVisible) {
        trackpadBounds = Rect.Zero
        onDispose { releaseJob?.cancel(); model.dragButton(pressed = false) }
    }
    Box(
        modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(model.connected, trackpadVisible) {
                if (!model.connected) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val onTrackpad = trackpadVisible && trackpadBounds.contains(down.position + origin)
                    val continuingDrag = model.dragging
                    var captured = onTrackpad || continuingDrag
                    if (captured) down.consume()
                    releaseJob?.cancel()
                    releaseJob = null
                    var releasedNormally = false
                    var travel = 0f
                    var fingers = 1
                    val motion = PointerMotion()
                    var scroll = 0f
                    var elapsed = 0L
                    var held = continuingDrag
                    val origins = mutableMapOf(down.id to down.position)
                    try {
                        do {
                            // Own the gesture before the surrounding scroll container consumes it.
                            val event = if (onTrackpad && !held && fingers == 1 && travel < viewConfiguration.touchSlop) {
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
                            if (fingers > 1 || travel > viewConfiguration.touchSlop) captured = true
                            if (fingers > 1 && model.dragging) model.dragButton(pressed = false)
                            val moving = event.changes.filter { it.pressed && it.previousPressed }
                            if (captured && moving.isNotEmpty()) {
                                val dx = moving.sumOf { it.positionChangeIgnoreConsumed().x.toDouble() }.toFloat() / moving.size
                                val dy = moving.sumOf { it.positionChangeIgnoreConsumed().y.toDouble() }.toFloat() / moving.size
                                if (moving.size >= 2) {
                                    scroll += -dy / 24.dp.toPx()
                                    val steps = scroll.toInt()
                                    if (steps != 0) { model.move(0, 0, steps); scroll -= steps }
                                } else if (fingers == 1) {
                                    motion.move(dx, dy, model.sensitivity / density) { x, y -> model.move(x, y) }
                                }
                            }
                            if (captured) event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) { releasedNormally = true; break }
                        } while (true)
                        val tap = travel < viewConfiguration.touchSlop && elapsed < viewConfiguration.longPressTimeoutMillis
                        if (tap && continuingDrag) model.dragButton(pressed = false)
                        else if (tap && onTrackpad && !held) model.click(right = fingers > 1)
                    } finally {
                        if (model.dragging) {
                            if (releasedNormally && fingers == 1) {
                                // A short lift lets the finger return from the edge without
                                // dropping a selection. A tap completes the drag immediately.
                                releaseJob = scope.launch {
                                    delay(650)
                                    model.dragButton(pressed = false)
                                }
                            } else model.dragButton(pressed = false)
                        }
                    }
                }
            },
    ) {
        content(Modifier.onGloballyPositioned { trackpadBounds = it.boundsInRoot() })
    }
}

@Composable
internal fun Trackpad(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().clip(KeyboardLayout.TrackpadShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .semantics { contentDescription = "Трекпад" })
}
