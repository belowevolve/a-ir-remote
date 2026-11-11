package belowevolve.airremote

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Drawn above the scroll viewport; disappears at an edge and never consumes input. */
internal fun Modifier.scrollFade(state: ScrollState, background: Color): Modifier = drawWithCache {
    val extent = 24.dp.toPx().coerceAtMost(size.height / 3)
    val top = Brush.verticalGradient(listOf(background, background.copy(alpha = 0f)), endY = extent)
    val bottom = Brush.verticalGradient(listOf(background.copy(alpha = 0f), background),
        startY = size.height - extent, endY = size.height)
    onDrawWithContent {
        drawContent()
        if (extent > 0 && state.maxValue != Int.MAX_VALUE) {
            if (state.canScrollBackward) drawRect(top, size = Size(size.width, extent),
                alpha = (state.value / extent).coerceIn(0f, 1f))
            if (state.canScrollForward) drawRect(bottom, topLeft = Offset(0f, size.height - extent),
                size = Size(size.width, extent), alpha = ((state.maxValue - state.value) / extent).coerceIn(0f, 1f))
        }
    }
}
