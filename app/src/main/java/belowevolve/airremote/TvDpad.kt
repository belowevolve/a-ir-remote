package belowevolve.airremote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun TvDpad(model: RemoteModel, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)
        .border(TvLayout.DpadBorder, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        .pointerInput(model.connected) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                fun keyAt(x: Float, y: Float) = dpadKey(x - size.width / 2f, y - size.height / 2f,
                    size.width / 2f, TvLayout.ConfirmSize.toPx() / 2f)
                val key = keyAt(down.position.x, down.position.y) ?: return@awaitEachGesture
                down.consume()
                val gesture = DpadGesture(key, down.position.x, down.position.y,
                    viewConfiguration.touchSlop, maxOf(32.dp.toPx(), viewConfiguration.touchSlop * 2))
                var heldOk = false
                var repeated = false
                var deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                var now = down.uptimeMillis
                fun press(code: Int) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    model.key(code)
                }
                try {
                    while (true) {
                        val event = if (gesture.moving || heldOk) awaitPointerEvent(PointerEventPass.Initial)
                        else withTimeoutOrNull((deadline - now).coerceAtLeast(1)) {
                            awaitPointerEvent(PointerEventPass.Initial)
                        }
                        if (event == null) {
                            repeated = true
                            if (key == 23) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                model.holdOk(true)
                                heldOk = true
                            } else press(key)
                            now = deadline
                            deadline += 80
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        now = change.uptimeMillis
                        event.changes.forEach { it.consume() }
                        // A second finger cancels this gesture instead of leaving OK held.
                        if (event.changes.any { it.id != down.id && it.pressed }) break
                        val swipe = gesture.move(change.position.x, change.position.y)
                        if (gesture.moving && heldOk) {
                            model.holdOk(false)
                            heldOk = false
                        }
                        swipe?.let(::press)
                        if (!change.pressed) {
                            if (!repeated) gesture.tap()?.let(::press)
                            break
                        }
                    }
                } finally { if (heldOk) model.holdOk(false) }
            }
        }) {
        // Centre each arrow in the ring between the OK button and outer edge.
        val arrowInset = ((maxWidth - TvLayout.ConfirmSize) / 4 - RemoteLayout.ActionSize / 2)
            .coerceAtLeast(0.dp)
        listOf(
            Triple(Icons.Rounded.KeyboardArrowUp, "Вверх", Alignment.TopCenter),
            Triple(Icons.Rounded.KeyboardArrowDown, "Вниз", Alignment.BottomCenter),
            Triple(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Влево", Alignment.CenterStart),
            Triple(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Вправо", Alignment.CenterEnd),
        ).forEachIndexed { index, (icon, label, alignment) ->
            Box(Modifier.align(alignment).padding(arrowInset).size(RemoteLayout.ActionSize)
                .semantics { onClick(label) { model.key(19 + index); true } }, contentAlignment = Alignment.Center) {
                Icon(icon, label, Modifier.size(TvLayout.DirectionIconSize), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Surface(Modifier.size(TvLayout.ConfirmSize).align(Alignment.Center)
            .semantics { onClick("OK") { model.key(23); true } }, shape = CircleShape,
            color = MaterialTheme.colorScheme.primary) {
            Box(contentAlignment = Alignment.Center) {
                Text("OK", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
