package belowevolve.airremote

import android.content.ComponentName
import android.content.Context
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Android keeps the selected assistant alive; audio capture still uses a visible FGS. */
class RemoteAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        if (VoiceControlService.enabled(this)) VoiceControlService.start(this)
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        VoiceControlService.start(this)
    }

    companion object {
        fun isSelected(context: Context): Boolean = isActiveService(context, ComponentName(context, RemoteAssistantService::class.java))
    }
}

class RemoteAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: android.os.Bundle?): VoiceInteractionSession =
        object : VoiceInteractionSession(this) {
            override fun onShow(args: android.os.Bundle?, showFlags: Int) {
                setUiEnabled(false)
                super.onShow(args, showFlags)
                VoiceControlService.start(this@RemoteAssistantSessionService)
                hide()
            }
        }
}
