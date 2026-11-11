package belowevolve.tvhome

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import belowevolve.shared.design.SeekText

/** A single standard seekTo, with playback-state confirmation. No app-specific routes or key loops. */
internal object MediaSeek {
    const val ACTION = "belowevolve.tvhome.SEEK_MEDIA"
    private val main = Handler(Looper.getMainLooper())

    fun request(context: Context, seconds: Int) {
        val app = context.applicationContext
        app.sendOrderedBroadcast(Intent(ACTION).setPackage(app.packageName).putExtra("seconds", seconds), null,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (resultCode != Activity.RESULT_OK) perform(app, seconds, null)
                }
            }, null, Activity.RESULT_CANCELED, null, null)
    }

    fun perform(context: Context, seconds: Int, foreground: String?) {
        require(seconds != 0 && seconds in -6000..6000)
        val sessions = try {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(
                ComponentName(context, MediaAccessService::class.java))
        } catch (_: SecurityException) {
            report(context, "Разрешите управление плеерами: настройки Air Home → Управление медиа")
            return
        }
        val selected = if (foreground == null) sessions.firstOrNull()
            else sessions.firstOrNull { it.packageName == foreground }
                ?: if (foreground == context.packageName || foreground.contains("backdrop"))
                    sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                else null
        val state = selected?.playbackState
        if (selected == null || state == null || state.position < 0 || state.state in
            setOf(PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR)) {
            report(context, "Откройте плеер с поддержкой системной перемотки")
            return
        }
        if (state.actions and PlaybackState.ACTION_SEEK_TO == 0L) {
            report(context, "Этот плеер не предоставляет точную перемотку")
            return
        }
        val target = MediaSeekTarget.calculate(state.position, state.lastPositionUpdateTime, SystemClock.elapsedRealtime(),
            state.playbackSpeed, state.state == PlaybackState.STATE_PLAYING,
            selected.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L, seconds)
        if (target.beforeMs == target.targetMs) {
            report(context, if (seconds < 0) "Уже в начале видео" else "Уже в конце видео"); return
        }
        var finished = false
        lateinit var callback: MediaController.Callback
        lateinit var timeout: Runnable
        fun finish(message: String) {
            if (finished) return
            finished = true
            selected.unregisterCallback(callback)
            main.removeCallbacks(timeout)
            report(context, message)
        }
        callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(updated: PlaybackState?) {
                if (updated != null && updated.lastPositionUpdateTime > state.lastPositionUpdateTime &&
                    kotlin.math.abs(updated.position - target.targetMs) < 1_500) {
                    Log.i("AirMediaSeek", "ACK package=${selected.packageName} position=${updated.position}")
                    finish(SeekText.accepted(((target.targetMs - target.beforeMs) / 1000).toInt()))
                }
            }
            override fun onSessionDestroyed() { finish("Плеер закрыл сеанс во время перемотки") }
        }
        timeout = Runnable { finish("${SeekText.sent(seconds)}; плеер не подтвердил позицию") }
        try {
            selected.registerCallback(callback, main)
            main.postDelayed(timeout, 3_000)
            Log.i("AirMediaSeek", "SEEK package=${selected.packageName} before=${target.beforeMs} target=${target.targetMs}")
            selected.transportControls.seekTo(target.targetMs)
        } catch (error: RuntimeException) {
            Log.w("AirMediaSeek", "Seek failed", error)
            finish("Не удалось выполнить перемотку в этом плеере")
        }
    }
    private fun report(context: Context, message: String) { Ui.toast(context, message) }
}
