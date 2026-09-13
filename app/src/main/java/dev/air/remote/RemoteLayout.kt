package dev.air.remote

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** App spacing and component geometry, complementing MaterialTheme's colors and typography. */
internal object RemoteLayout {
    val ScreenPadding = 16.dp
    val SmallGap = 8.dp
    val Gap = 12.dp
    val HeaderSize = 52.dp
    val HeaderIconSize = 26.dp
    val HeaderShape = RoundedCornerShape(18.dp)
    val TabHeight = 48.dp
    val TabIconSize = 20.dp
    val ActionSize = 64.dp
    val ActionIconSize = 28.dp
    val InlineIconSize = 22.dp
    val ActionShape = RoundedCornerShape(22.dp)
    val DialogInset = 24.dp
    val DialogMaxWidth = 440.dp
    val DialogShape = RoundedCornerShape(24.dp)
}

internal object KeyboardLayout {
    val MinKeyHeight = 36.dp
    val KeyHeight = 48.dp
    val KeyGap = 2.dp
    val RowGap = 3.dp
    val EdgePadding = 2.dp
    val ArrowWidth = 42.dp
    val KeyShape = RoundedCornerShape(7.dp)
    val ToolbarSize = 40.dp
    val MinTrackpadHeight = 96.dp
    val MaxTrackpadHeight = 240.dp
    val ExpandedTrackpadMinHeight = 160.dp
    val TrackpadShape = RoundedCornerShape(16.dp)
    const val RowCount = 8

    // Header, two content gaps and top/bottom screen padding.
    private val chromeHeight = RemoteLayout.HeaderSize + RemoteLayout.SmallGap * 4
    private val rowGapsHeight = RowGap * (RowCount - 1)

    fun keyHeight(available: Dp): Dp =
        ((available - chromeHeight - MinTrackpadHeight - rowGapsHeight) / RowCount)
            .coerceIn(MinKeyHeight, KeyHeight)

    fun trackpadHeight(available: Dp, keyHeight: Dp, keyboardVisible: Boolean): Dp =
        if (keyboardVisible) {
            (available - chromeHeight - keyHeight * RowCount - rowGapsHeight)
                .coerceIn(MinTrackpadHeight, MaxTrackpadHeight)
        } else {
            (available - chromeHeight - ToolbarSize).coerceAtLeast(ExpandedTrackpadMinHeight)
        }
}

internal object TvLayout {
    val MaxWidth = 560.dp
    val ColorKeyHeight = 32.dp
    val ColorKeyShape = RoundedCornerShape(13.dp)
    val AppIconSize = 32.dp
    val DpadMinSize = 224.dp
    val DpadMaxSize = 252.dp
    val DpadInset = 4.dp
    val DpadBorder = 1.dp
    val ConfirmSize = 84.dp
}
