package belowevolve.airremote

import android.content.Context
import androidx.compose.runtime.Composable
import java.io.File

/** The standard APK has no recognizer, assistant services or model assets. */
internal object VoiceFeature {
    const val available = false
    const val active = false
    private var cleaned = false

    fun restore(context: Context) {
        if (cleaned) return
        cleaned = true
        // Switching editions preserves TV/PC pairing, but must release the old voice model's space.
        val model = File(context.noBackupFilesDir, "voice")
        if (model.exists()) Thread({ model.deleteRecursively() }, "air-voice-cleanup").start()
    }

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun SettingsDialog(hasTv: Boolean, onDismiss: () -> Unit) = Unit
}
