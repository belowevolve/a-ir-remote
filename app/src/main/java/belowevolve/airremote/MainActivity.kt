package belowevolve.airremote

import android.annotation.SuppressLint
import android.Manifest
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val model: RemoteModel by viewModels()
    private val pcModel: PcRemoteModel by viewModels()
    private var pcMode = false
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            if (MicrophoneService.status.value.active) model.message = "Сначала останови микрофон для ПК"
            else model.startVoice()
        } else model.message = "Для голоса нужен доступ к микрофону"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            RemoteTheme {
                CompositionLocalProvider(LocalVolumeKeyHandler provides ::handleVolumeKey) {
                    RemoteModes(pcModel, onPcActivated = { model.stopVoice(); model.hideKeyboard() }, onModeChanged = { pcMode = it; model.setTvMode(!it) }) {
                        RemoteScreen(model) {
                            if (model.recording) model.stopVoice() else microphone.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handleVolumeKey(event) || super.dispatchKeyEvent(event)

    private fun handleVolumeKey(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> 2
            KeyEvent.KEYCODE_VOLUME_MUTE -> 3
            else -> return false
        }
        // Consume the entire gesture, including repeats and key-up. Otherwise
        // Android changes the phone volume after the initial remote command.
        if (event.action == KeyEvent.ACTION_DOWN &&
            (direction != 3 || event.repeatCount == 0) && !event.isCanceled) {
            if (pcMode) pcModel.volume(direction) else model.key(event.keyCode)
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        model.onMicrophonePermissionNeeded = { microphone.launch(Manifest.permission.RECORD_AUDIO) }
        model.resume()
        pcModel.resume()
    }

    override fun onStop() {
        model.onMicrophonePermissionNeeded = null
        model.pause()
        pcModel.pause()
        super.onStop()
    }
}
