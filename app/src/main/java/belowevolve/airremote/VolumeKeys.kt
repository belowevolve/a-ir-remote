package belowevolve.airremote

import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

internal val LocalVolumeKeyHandler = staticCompositionLocalOf<(KeyEvent) -> Boolean> { { false } }

/** Dialogs own a separate window and do not dispatch key events to the activity. */
@Composable
internal fun CaptureDialogVolumeKeys() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val handle by rememberUpdatedState(LocalVolumeKeyHandler.current)
    DisposableEffect(window) {
        val original = window?.callback
        val callback = original?.let {
            object : Window.Callback by it {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                    handle(event) || original.dispatchKeyEvent(event)
            }
        }
        if (callback != null) window.callback = callback
        onDispose {
            if (callback != null && window.callback === callback) window.callback = original
        }
    }
}
