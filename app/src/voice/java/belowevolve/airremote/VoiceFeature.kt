package belowevolve.airremote

import android.content.Context
import androidx.compose.runtime.Composable

internal object VoiceFeature {
    const val available = true
    val active: Boolean get() = VoiceControlService.status.value.active

    fun restore(context: Context) {
        if (VoiceControlService.enabled(context)) VoiceControlService.start(context)
    }

    @Composable
    fun SettingsDialog(hasTv: Boolean, onDismiss: () -> Unit) = VoiceControlDialog(hasTv, onDismiss)
}
