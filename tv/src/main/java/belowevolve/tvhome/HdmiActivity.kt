package belowevolve.tvhome

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.FileDescriptor
import java.io.PrintWriter

/* Hardware passthrough only. Home and its refresh work stop while this screen is visible. */
class HdmiActivity : QuietActivity() {
    private var video: TvView? = null
    private var audio: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private var started: Boolean = false
    private var resumed: Boolean = false
    private var tuned: Boolean = false
    private var audioAllowed: Boolean = false
    private var videoAvailable: Boolean = false
    private var waitingForVideo: Boolean = false
    private var reconnectAttempts: Int = 0
    private var unavailableReason: Int = TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
    private val signalTimeout = HdmiSignalTimeout()
    private val autoHome = Runnable {
        val remaining = signalTimeout.remaining(SystemClock.elapsedRealtime())
        if (resumed && remaining == 0L) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WakeTrace.record(this, "hdmi_no_signal_home")
            Log.i("AirHdmi", "NO_SIGNAL_HOME input=$input reason=$unavailableReason")
            onRemoteBack()
        } else if (remaining != null) scheduleAutoHome()
    }
    private val main: Handler = Handler(Looper.getMainLooper())
    private var showNoVideo by mutableStateOf(false)
    private var input: String? = null
    private var tuneStarted: Long = 0
    private val reconnect: Runnable = Runnable {
        if (!resumed || tuned || reconnectAttempts != 0) return@Runnable
        reconnectAttempts++
        video!!.reset()
        tune()
    }
    private val noVideoTimeout: Runnable = Runnable {
        waitingForVideo = false
        if (!resumed || videoAvailable || isAudioOnly() || showNoVideo) return@Runnable
        WakeTrace.record(this, "hdmi_no_video")
        showNoVideo = true
        signalTimeout.unavailable(SystemClock.elapsedRealtime(), false)
        scheduleAutoHome()
        getSharedPreferences("home", 0)
            .edit()
            .putLong("hdmi_last_no_video_time", System.currentTimeMillis())
            .putString("hdmi_last_no_video_input", input)
            .putInt("hdmi_last_no_video_reason", unavailableReason)
            .apply()
        Log.w("AirHdmi", "NO_VIDEO_UI input=" + input + " reason=" + unavailableReason)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Ui.fullscreen(this)
        getWindow().setBackgroundDrawable(ColorDrawable(Color.BLACK))
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        video = TvView(this)
        video!!.setBackgroundColor(android.graphics.Color.BLACK)
        video!!.setSoundEffectsEnabled(false)
        video!!.setCallback(
            object : TvView.TvInputCallback() {
                override fun onVideoAvailable(id: String) {
                    if (started) {
                        WakeTrace.record(this@HdmiActivity, "hdmi_video")
                        videoAvailable = true
                        signalTimeout.available()
                        main.removeCallbacks(autoHome)
                        reconnectAttempts = 0
                        main.removeCallbacks(reconnect)
                        getSharedPreferences("home", 0)
                            .edit()
                            .putLong("hdmi_last_video_time", System.currentTimeMillis())
                            .apply()
                        stopWaiting()
                        val hadMessage: Boolean = showNoVideo
                        showNoVideo = false
                        if (hadMessage) video!!.requestFocus()
                        Log.i(
                            "AirHdmi",
                            ("VIDEO_AVAILABLE input=" +
                                id +
                                " tune_ms=" +
                                (SystemClock.elapsedRealtime() - tuneStarted)),
                        )
                    }
                }

                override fun onVideoUnavailable(id: String, reason: Int) {
                    if (started) {
                        videoAvailable = false
                        unavailableReason = reason
                        if (isAudioOnly()) {
                            stopWaiting()
                            showNoVideo = false
                        }
                        awaitVideo()
                        Log.i("AirHdmi", "VIDEO_UNAVAILABLE input=" + id + " reason=" + reason)
                    }
                }

                override fun onDisconnected(id: String) {
                    tuned = false
                    videoAvailable = false
                    unavailableReason = TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN
                    awaitVideo()
                    scheduleReconnect()
                    Log.w("AirHdmi", "DISCONNECTED input=" + id)
                }

                override fun onConnectionFailed(id: String) {
                    tuned = false
                    videoAvailable = false
                    unavailableReason = TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN
                    awaitVideo()
                    scheduleReconnect()
                    Log.w("AirHdmi", "CONNECTION_FAILED input=" + id)
                }
            }
        )
        setContent {
            HomeTheme {
                Box(Modifier.fillMaxSize().background(ComposeColor.Black)) {
                    AndroidView(
                        factory = { checkNotNull(video) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (showNoVideo)
                        Surface(
                            Modifier.width(448.dp).align(Alignment.Center),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                        ) {
                            Column(
                                Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("Нет изображения с HDMI", fontSize = 24.sp)
                                Row(
                                    Modifier.padding(top = 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    FocusFirst(Modifier.weight(1f)) {
                                        TvAction("Главный экран", it) { onRemoteBack() }
                                    }
                                    TvAction("Повторить", Modifier.weight(1f)) {
                                        showNoVideo = false
                                        stopWaiting()
                                        signalTimeout.available()
                                        video!!.reset()
                                        videoAvailable = false
                                        tuned = false
                                        reconnectAttempts = 0
                                        tune()
                                    }
                                }
                            }
                        }
                }
            }
        }
        audio = getSystemService<AudioManager>(AudioManager::class.java)
        focus =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener({ change ->
                    audioAllowed = change == AudioManager.AUDIOFOCUS_GAIN
                    updateVolume()
                })
                .build()
        input = selectedInput(getIntent())
        if (input == null) {
            Ui.toast(this, "Вход HDMI недоступен")
            finish()
        }
    }

    private fun selectedInput(intent: Intent): String? {
        var requested: String? = intent.getStringExtra("input")
        if (requested == null)
            requested =
                getSharedPreferences("home", 0)
                    .getString("last_hdmi", "com.mediatek.tvinput/.hdmi.HDMIInputService/HW8")
        for (info: TvInputInfo in
            getSystemService<TvInputManager>(TvInputManager::class.java).getTvInputList()) if (
            info.getType() == TvInputInfo.TYPE_HDMI && info.getId() == requested
        )
            return requested
        return null
    }

    private fun tune() {
        if (!started || !resumed || input == null || tuned) return
        tuneStarted = SystemClock.elapsedRealtime()
        videoAvailable = false
        unavailableReason = TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
        tuned = true
        val selected = input ?: return
        video!!.tune(selected, TvContract.buildChannelUriForPassthroughInput(selected))
        getSharedPreferences("home", 0).edit().putString("last_hdmi", input).apply()
        updateVolume()
        video!!.requestFocus()
        awaitVideo()
        Log.i("AirHdmi", "TUNE input=" + input!!)
    }

    private fun updateVolume() {
        if (tuned) video!!.setStreamVolume(if (resumed && audioAllowed) 1f else 0f)
    }

    private fun isAudioOnly() =
        unavailableReason == TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY

    private fun scheduleAutoHome() {
        main.removeCallbacks(autoHome)
        signalTimeout.remaining(SystemClock.elapsedRealtime())?.let {
            main.postDelayed(autoHome, it)
        }
    }

    private fun awaitVideo() {
        if (videoAvailable || isAudioOnly()) signalTimeout.available()
        else if (showNoVideo) signalTimeout.unavailable(SystemClock.elapsedRealtime(), false)
        scheduleAutoHome()
        if (!resumed || videoAvailable || isAudioOnly() || showNoVideo) return
        val noSignalConfirmed = unavailableReason == TvInputManager.VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED ||
            unavailableReason == TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL ||
            input?.let { selected ->
                runCatching {
                    getSystemService(TvInputManager::class.java).getInputState(selected) == TvInputManager.INPUT_STATE_DISCONNECTED
                }.getOrDefault(false)
            } == true
        if (noSignalConfirmed) {
            stopWaiting()
            noVideoTimeout.run()
            return
        }
        if (waitingForVideo) return
        waitingForVideo = true
        main.postDelayed(noVideoTimeout, 5000)
    }

    private fun stopWaiting() {
        main.removeCallbacks(noVideoTimeout)
        waitingForVideo = false
    }

    private fun scheduleReconnect() {
        if (!resumed || reconnectAttempts != 0) return
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, 500)
    }

    protected override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val next: String? = selectedInput(intent)
        if (next == null) {
            Ui.toast(this, "Вход HDMI недоступен")
            return
        }
        if (next != input) {
            video!!.reset()
            tuned = false
            reconnectAttempts = 0
            videoAvailable = false
            showNoVideo = false
            stopWaiting()
            signalTimeout.available()
            input = next
        }
        tune()
    }

    override fun onStart() {
        super.onStart()
        started = true
        reconnectAttempts = 0
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        resumed = isForeground
        WakeTrace.record(this, "hdmi_resume")
        getSharedPreferences("home", 0)
            .edit()
            .putLong("hdmi_last_resume_time", System.currentTimeMillis())
            .apply()
        Ui.fullscreen(this)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        signalTimeout.resume(SystemClock.elapsedRealtime(), videoAvailable, isAudioOnly(), showNoVideo)
        tune()
        audioAllowed = audio!!.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        updateVolume()
        awaitVideo()
        Log.i("AirHdmi", "RESUME audio_focus=" + audioAllowed)
    }

    override fun onPause() {
        isForeground = false
        resumed = isForeground
        stopWaiting()
        signalTimeout.pause()
        main.removeCallbacks(autoHome)
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        main.removeCallbacks(reconnect)
        audioAllowed = false
        updateVolume()
        audio!!.abandonAudioFocusRequest(focus!!)
        super.onPause()
    }

    override fun onStop() {
        WakeTrace.record(this, "hdmi_stop")
        started = false
        signalTimeout.pause()
        main.removeCallbacks(autoHome)
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        video!!.reset()
        tuned = false
        videoAvailable = false
        showNoVideo = false
        Log.i("AirHdmi", "RELEASE")
        super.onStop()
    }

    override fun onRemoteBack() {
        startActivity(Actions.homeIntent(this))
        finish()
    }

    override fun dump(
        prefix: String,
        fd: FileDescriptor?,
        writer: PrintWriter,
        args: Array<String>?,
    ) {
        super.dump(prefix, fd, writer, args)
        WakeTrace.dump(this, prefix, writer)
        writer.println(
            (prefix +
                "AirHdmi input=" +
                input +
                " resumed=" +
                resumed +
                " tuned=" +
                tuned +
                " videoAvailable=" +
                videoAvailable +
                " waiting=" +
                waitingForVideo +
                " messageVisible=" +
                showNoVideo +
                " autoHomeMs=" +
                signalTimeout.remaining(SystemClock.elapsedRealtime()) +
                " reason=" +
                unavailableReason)
        )
        val p: android.content.SharedPreferences = getSharedPreferences("home", 0)
        writer.println(
            (prefix +
                "AirHdmi lastNoVideoTime=" +
                p.getLong("hdmi_last_no_video_time", 0) +
                " lastNoVideoInput=" +
                p.getString("hdmi_last_no_video_input", "") +
                " lastNoVideoReason=" +
                p.getInt("hdmi_last_no_video_reason", -1))
        )
        writer.println(
            (prefix +
                "AirHdmi lastResumeTime=" +
                p.getLong("hdmi_last_resume_time", 0) +
                " lastVideoTime=" +
                p.getLong("hdmi_last_video_time", 0))
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.getKeyCode() == KeyEvent.KEYCODE_TV_INPUT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                Actions.run(this, "inputs")
            return true
        }
        if (
            (event.getAction() == KeyEvent.ACTION_DOWN &&
                event.getRepeatCount() == 0 &&
                Actions.color(this, event.getKeyCode()))
        )
            return true
        return super.dispatchKeyEvent(event)
    }

    companion object {
        var isForeground: Boolean = false
            private set

        fun intent(context: Context, input: String?): Intent {
            val intent: Intent =
                Intent(context, HdmiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (input != null) intent.putExtra("input", input)
            return intent
        }
    }
}
