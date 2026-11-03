package belowevolve.airremote

import android.os.SystemClock
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
internal fun TvDpad(
    connected: Boolean,
    onKey: (Int) -> Unit,
    onHoldOk: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val sendKey by rememberUpdatedState(onKey)
    val holdOk by rememberUpdatedState(onHoldOk)
    BoxWithConstraints(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)
        .border(TvLayout.DpadBorder, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        .pointerInput(connected) {
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
                var arrowRepeated = false
                val repeat = DpadRepeat(viewConfiguration.touchSlop,
                    okHoldDelayMillis = viewConfiguration.longPressTimeoutMillis)
                repeat.update(key, down.position.x, down.position.y, SystemClock.uptimeMillis())
                fun press(code: Int) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    sendKey(code)
                }
                try {
                    while (true) {
                        val deadline = repeat.deadline.takeUnless { heldOk }
                        val event = if (deadline == null) awaitPointerEvent(PointerEventPass.Initial)
                        else withTimeoutOrNull((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) {
                            awaitPointerEvent(PointerEventPass.Initial)
                        }
                        if (event == null) {
                            val code = repeat.poll(SystemClock.uptimeMillis()) ?: continue
                            repeated = true
                            if (code == 23) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                holdOk(true)
                                heldOk = true
                            } else {
                                arrowRepeated = true
                                press(code)
                            }
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        event.changes.forEach { it.consume() }
                        // A second finger cancels this gesture instead of leaving OK held.
                        if (event.changes.any { it.id != down.id && it.pressed }) break
                        val region = keyAt(change.position.x, change.position.y) ?: break
                        if (!change.pressed) {
                            // Release coordinates can drift. They may cancel a tap,
                            // but must not inject another swipe step.
                            gesture.move(change.position.x, change.position.y)
                            if (!repeated && region == key) gesture.tap()?.let(::press)
                            break
                        }
                        val swipe = gesture.move(change.position.x, change.position.y)
                        if (heldOk && (gesture.moving || region != 23)) {
                            holdOk(false)
                            heldOk = false
                        }
                        // Returning to the centre is a stop after auto-repeat,
                        // rather than an unwanted step in the opposite direction.
                        if (swipe != null && (!arrowRepeated || region == swipe)) {
                            press(swipe)
                            arrowRepeated = false
                        }
                        // After a swipe, only its matching arrow can repeat. Passing
                        // through OK or an unrelated sector never starts a new hold.
                        val holdKey = if (gesture.moving) region.takeIf { it == gesture.direction }
                            else key.takeIf { it == region }
                        repeat.update(holdKey, change.position.x, change.position.y,
                            SystemClock.uptimeMillis(), restart = swipe != null)
                    }
                } finally { if (heldOk) holdOk(false) }
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
                .semantics { onClick(label) { sendKey(19 + index); true } }, contentAlignment = Alignment.Center) {
                Icon(icon, label, Modifier.size(TvLayout.DirectionIconSize), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Surface(Modifier.size(TvLayout.ConfirmSize).align(Alignment.Center)
            .semantics { onClick("OK") { sendKey(23); true } }, shape = CircleShape,
            color = MaterialTheme.colorScheme.primary) {
            Box(contentAlignment = Alignment.Center) {
                Text("OK", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
