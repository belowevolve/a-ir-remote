package belowevolve.tvhome

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle

/** Fixed remote routes; callers cannot supply arbitrary packages or components. */
class RemoteLaunchActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val uri = intent.data
        val action = RemoteLaunchAction.fromRoute(uri?.scheme, uri?.host, uri?.path)
        when (action) {
            RemoteLaunchAction.FILM, RemoteLaunchAction.YOUTUBE, RemoteLaunchAction.PC, RemoteLaunchAction.WAKE -> {
                val context = applicationContext
                val target = action.shortcut
                // Share the physical shortcuts' dream dismissal and target restoration.
                // The accessibility service lives in :keys, so a static Activity reference cannot work.
                sendOrderedBroadcast(
                    Intent(RemoteLaunchAction.BROADCAST).setPackage(packageName).putExtra("path", uri?.path),
                    null,
                    object : BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) {
                            if (resultCode != RESULT_OK) {
                                // Keep ordinary app launching usable when the optional key service is disabled.
                                android.util.Log.w("AirHome", "Remote launch without screensaver helper")
                                target?.let { Actions.run(context, it) }
                            }
                        }
                    },
                    null, RESULT_CANCELED, null, null,
                )
            }
            RemoteLaunchAction.VOLUME -> RemoteLaunchAction.volumePercent(intent.data?.getQueryParameter("level"))?.let { percent ->
                val audio = getSystemService(AudioManager::class.java)
                val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                val level = kotlin.math.round(maximum * percent / 100.0).toInt().coerceIn(minimum, maximum)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
                if (percent > 0) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            RemoteLaunchAction.SEEK -> RemoteLaunchAction.seekSeconds(uri?.getQueryParameter("seconds"))?.let {
                MediaSeek.request(applicationContext, it)
            }
            null -> Unit
        }
        finish()
    }
}
