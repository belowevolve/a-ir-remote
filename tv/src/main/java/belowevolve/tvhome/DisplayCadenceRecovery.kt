package belowevolve.tvhome

import android.app.Activity
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Foreground-only recovery for the measured T31 firmware's retained video clock. */
internal class DisplayCadenceRecovery(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private var active = false
    private var epoch = 0
    private var sample: Choreographer.FrameCallback? = null
    private var job: Recovery? = null
    private var attempted = false
    private var focusTries = 0
    private var sampleTries = 0
    private val begin = Runnable { sampleClock() }

    fun resume() {
        if (!supported || !enabled(activity)) return
        active = true
        attempted = false
        focusTries = 0
        sampleTries = 0
        epoch++
        sample?.let(choreographer::removeFrameCallback)
        sample = null
        main.removeCallbacks(begin)
        main.postDelayed(begin, 250)
    }

    fun pause() {
        active = false
        epoch++
        main.removeCallbacks(begin)
        sample?.let(choreographer::removeFrameCallback)
        sample = null
        job?.cancel()
    }

    private fun sampleClock() {
        if (!active || sample != null || !enabled(activity) ||
            !activity.getSystemService(PowerManager::class.java).isInteractive) return
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay
        if (display.refreshRate !in 59f..61f) return
        if (!activity.window.decorView.hasWindowFocus()) {
            if (++focusTries <= 24) main.postDelayed(begin, 250)
            return
        }
        val generation = epoch
        val intervals = ArrayList<Long>(32)
        val arrivals = ArrayList<Long>(32)
        var previousFrame = 0L
        var previousArrival = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!active || epoch != generation) return
                val now = System.nanoTime()
                if (previousFrame != 0L) {
                    intervals += frameTimeNanos - previousFrame
                    arrivals += now - previousArrival
                }
                previousFrame = frameTimeNanos
                previousArrival = now
                if (intervals.size < 32) {
                    choreographer.postFrameCallback(this)
                    return
                }
                sample = null
                val median = intervals.sorted()[16]
                val matching = intervals.count { it in 18_000_000L..22_000_000L || it in 38_000_000L..42_000_000L }
                val steady50 = median in 18_000_000L..22_000_000L &&
                    matching >= 16
                Log.i(TAG, "sample_ns=$median arrival_ns=${arrivals.sorted()[16]} matching=$matching steady50=$steady50")
                if (steady50 && !attempted) recover()
                else if (!attempted && median in 18_000_000L..22_000_000L && ++sampleTries < 3)
                    main.postDelayed(begin, 250)
            }
        }
        sample = callback
        choreographer.postFrameCallback(callback)
    }

    private fun recover() {
        if (!active || job != null) return
        val parent = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        attempted = true
        val recovery = Recovery(parent)
        job = recovery
        recovery.attach()
    }

    private inner class Recovery(private val parent: FrameLayout) : SurfaceHolder.Callback {
        private val cancelled = AtomicBoolean()
        private val started = AtomicBoolean()
        private val view = SurfaceView(activity).apply {
            setZOrderOnTop(true)
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun attach() {
            view.holder.addCallback(this)
            parent.addView(view, FrameLayout.LayoutParams(2, 2, Gravity.BOTTOM or Gravity.END))
        }

        fun cancel() {
            cancelled.set(true)
            parent.removeView(view)
            if (!started.get()) {
                view.holder.removeCallback(this)
                if (job === this) job = null
            }
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            if (cancelled.get()) return
            if (started.compareAndSet(false, true))
                Thread({ decode(holder.surface) }, "air-display-recovery").start()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
        override fun surfaceDestroyed(holder: SurfaceHolder) { cancelled.set(true) }

        private fun decode(surface: Surface) {
            val at = SystemClock.elapsedRealtime()
            var codec: MediaCodec? = null
            var extractor: MediaExtractor? = null
            var queued = 0
            var rendered = 0
            val presented = AtomicInteger()
            try {
                val input = MediaExtractor()
                extractor = input
                activity.assets.openFd("display/clock60.mp4").use {
                    input.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                val track = (0 until input.trackCount).first {
                    input.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                input.selectTrack(track)
                val format = input.getTrackFormat(track).apply { setFloat(MediaFormat.KEY_FRAME_RATE, 60f) }
                val decoder = MediaCodec.createByCodecName("OMX.MS.AVC.Decoder")
                codec = decoder
                decoder.setOnFrameRenderedListener({ _, _, _ -> presented.incrementAndGet() }, main)
                decoder.configure(format, surface, null, 0)
                decoder.start()
                val base = System.nanoTime() + 50_000_000L
                val origin = input.sampleTime
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                while (!cancelled.get() && !outputEnded && SystemClock.elapsedRealtime() - at < 1800) {
                    if (!inputEnded) {
                        val index = decoder.dequeueInputBuffer(5000)
                        if (index >= 0) {
                            val count = if (queued >= 18) -1 else input.readSampleData(requireNotNull(decoder.getInputBuffer(index)), 0)
                            if (count < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(index, 0, count, input.sampleTime - origin, 0)
                                queued++
                                input.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 5000)
                    if (index >= 0) {
                        if (info.size > 0 && !cancelled.get()) {
                            val timestamp = base + info.presentationTimeUs * 1000L
                            while (!cancelled.get() && System.nanoTime() + 2_000_000L < timestamp)
                                SystemClock.sleep(1)
                            if (cancelled.get()) decoder.releaseOutputBuffer(index, false)
                            else {
                                decoder.releaseOutputBuffer(index, timestamp)
                                rendered++
                            }
                        } else decoder.releaseOutputBuffer(index, false)
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    }
                }
                // Dequeueing an output buffer does not mean the vendor compositor presented it.
                // Wait for its bounded render acknowledgement before releasing the decoder.
                val deadline = SystemClock.elapsedRealtime() + 500
                while (!cancelled.get() && presented.get() < rendered && SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(5)
            } catch (error: Exception) {
                Log.w(TAG, "Recovery stopped", error)
            } finally {
                runCatching { codec?.release() }
                runCatching { extractor?.release() }
                Log.i(TAG, "finished queued=$queued rendered=$rendered presented=${presented.get()} cancelled=${cancelled.get()} elapsed_ms=${SystemClock.elapsedRealtime() - at}")
                main.post {
                    view.holder.removeCallback(this)
                    parent.removeView(view)
                    if (job === this) job = null
                    if (active) main.postDelayed(begin, 250)
                }
            }
        }
    }

    companion object {
        private const val TAG = "AirDisplayCadence"
        val supported: Boolean
            get() = Build.VERSION.SDK_INT == 34 && Build.HARDWARE == "m7632" &&
                Build.DEVICE == "daan" && Build.ID == "UKNV.260410.001" &&
                Build.VERSION.INCREMENTAL == "15184903"

        fun enabled(context: Context): Boolean =
            context.getSharedPreferences("display", 0).getBoolean("recover_cadence", true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("display", 0).edit().putBoolean("recover_cadence", enabled).apply()
        }
    }
}
