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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics

@Composable
internal fun TvDpad(model: RemoteModel, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)
        .border(TvLayout.DpadBorder, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        .pointerInput(model.connected) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                fun keyAt(x: Float, y: Float) = dpadKey(x - size.width / 2f, y - size.height / 2f,
                    size.width / 2f, TvLayout.ConfirmSize.toPx() / 2f)
                var key = keyAt(down.position.x, down.position.y)
                if (key == null) return@awaitEachGesture
                down.consume()
                var heldOk = false
                var deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                var now = down.uptimeMillis
                fun pressArrow() {
                    if (key != null && key != 23) model.key(key!!)
                }
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                pressArrow()
                try {
                    while (true) {
                        val event = if (key == null || heldOk) awaitPointerEvent(PointerEventPass.Initial)
                        else withTimeoutOrNull((deadline - now).coerceAtLeast(1)) {
                            awaitPointerEvent(PointerEventPass.Initial)
                        }
                        if (event == null) {
                            if (key == 23) { model.holdOk(true); heldOk = true }
                            else pressArrow()
                            now = deadline
                            deadline += 80
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        now = change.uptimeMillis
                        val next = keyAt(change.position.x, change.position.y)
                        event.changes.forEach { it.consume() }
                        if (!change.pressed) {
                            if (key == 23 && next == 23 && !heldOk) model.key(23)
                            break
                        }
                        if (next != key) {
                            if (heldOk) { model.holdOk(false); heldOk = false }
                            key = next
                            deadline = now + viewConfiguration.longPressTimeoutMillis
                            pressArrow()
                        }
                    }
                } finally { if (heldOk) model.holdOk(false) }
            }
        }) {
        listOf(
            Triple(Icons.Rounded.KeyboardArrowUp, "Вверх", Alignment.TopCenter),
            Triple(Icons.Rounded.KeyboardArrowDown, "Вниз", Alignment.BottomCenter),
            Triple(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Влево", Alignment.CenterStart),
            Triple(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Вправо", Alignment.CenterEnd),
        ).forEachIndexed { index, (icon, label, alignment) ->
            Box(Modifier.align(alignment).padding(TvLayout.DpadInset).size(RemoteLayout.ActionSize)
                .semantics { onClick(label) { model.key(19 + index); true } }, contentAlignment = Alignment.Center) {
                Icon(icon, label, Modifier.size(RemoteLayout.ActionIconSize), tint = Color(0xFFE0E5EC))
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
