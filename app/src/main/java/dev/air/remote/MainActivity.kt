package dev.air.remote

import android.annotation.SuppressLint
import android.Manifest
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
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
                RemoteModes(pcModel, onPcActivated = { model.stopVoice(); model.hideKeyboard() }, onModeChanged = { pcMode = it }) {
                    RemoteScreen(model) {
                        if (model.recording) model.stopVoice() else microphone.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.action == KeyEvent.ACTION_DOWN) && (event.repeatCount == 0)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (pcMode) pcModel.volume(1) else model.key(24)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (pcMode) pcModel.volume(2) else model.key(25)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    if (pcMode) pcModel.volume(3) else model.key(164)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        model.resume()
        pcModel.resume()
    }

    override fun onStop() {
        model.pause()
        pcModel.pause()
        super.onStop()
    }
}
