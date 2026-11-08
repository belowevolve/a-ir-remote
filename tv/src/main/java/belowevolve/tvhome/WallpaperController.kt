package belowevolve.tvhome

import android.content.Context
import android.graphics.*
import android.os.*
import java.io.File
import java.util.concurrent.*
import java.util.function.BiConsumer

/*
 * Local display and remote catalogue use independent queues; no service or periodic background job.
 */
class WallpaperController(
    context: Context,
    private var listener: BiConsumer<Bitmap, WallpaperTheme?>?,
) {
    private val context: Context
    private val main: Handler = Handler(Looper.getMainLooper())
    private val decode: ExecutorService = Executors.newSingleThreadExecutor()
    private val network: ExecutorService = Executors.newSingleThreadExecutor()
    private var visible: Boolean = false
    @Volatile private var closed: Boolean = false
    @Volatile private var generation: Int = 0
    private var hourShown: Long = -1
    private var styleShown: String = ""
    private val refresh: Runnable = Runnable({ this.refreshNetwork() })

    private fun refreshNetwork() {
        if (!visible || closed) return
        network.execute(
            {
                if (WallpaperStore(context).refresh())
                    main.post(
                        {
                            if (closed) return@post
                            invalidate()
                            if (visible) update()
                        },
                    )
            },
        )
    }

    init {
        this.context = context.getApplicationContext()
    }

    fun invalidate() {
        hourShown = -1
    }

    fun resume() {
        visible = true
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 5000)
    }

    fun stop() {
        visible = false
        main.removeCallbacks(refresh)
    }

    fun close() {
        closed = true
        generation++
        listener = null
        main.removeCallbacksAndMessages(null)
        decode.shutdownNow()
        network.shutdownNow()
    }

    fun update() {
        val hour: Long = System.currentTimeMillis() / 3600000
        val style: String = BackdropStyle(context).fingerprint()
        if (hour == hourShown && style == styleShown) return
        styleShown = style
        hourShown = hour
        val request: Int = ++generation
        decode.execute(
            {
                if (request != generation || closed) return@execute
                val store: WallpaperStore = WallpaperStore(context)
                val selected: File? = store.selected(hour)
                val result: Prepared = prepare(context, selected)
                val ready: Bitmap? = result.image
                val readyTheme: WallpaperTheme? = result.theme
                val themeKey: String = result.key
                main.post(
                    {
                        if (closed || request != generation) {
                            if (ready != null) ready.recycle()
                            return@post
                        }
                        if (ready != null) {
                            if (readyTheme != null) readyTheme.save(context, themeKey)
                            listener!!.accept(ready, readyTheme)
                        }
                    },
                )
            },
        )
    }

    class Prepared(val image: Bitmap?, val theme: WallpaperTheme?, val key: String)

    companion object {
        // Keep the persisted theme/cache identity while giving the bundled resource a clear name.
        private const val FallbackThemeKey = "landscape_02"

        fun prepare(context: Context, selected: File?): Prepared {
            val themeKey: String =
                if (selected == null) FallbackThemeKey
                else selected.getName() + ":" + selected.lastModified()
            val opt: BitmapFactory.Options = BitmapFactory.Options()
            opt.inScaled = false
            opt.inMutable = true
            val image: Bitmap?
            if (selected != null) {
                val bounds: BitmapFactory.Options = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                BitmapFactory.decodeFile(selected.getPath(), bounds)
                var sample: Int = 1
                while (
                    bounds.outWidth / (sample * 2) >= 1920 &&
                        bounds.outHeight / (sample * 2) >= 1080
                ) sample *= 2
                opt.inSampleSize = sample
                image = BitmapFactory.decodeFile(selected.getPath(), opt)
            } else {
                opt.inSampleSize = 2
                image =
                    BitmapFactory.decodeResource(
                        context.getResources(),
                        R.raw.fallback_wallpaper,
                        opt,
                    )
            }
            var theme: WallpaperTheme? = null
            var prepared: Bitmap? = image
            if (image != null) {
                if (image.getWidth() != 1920 || image.getHeight() != 1080) {
                    prepared = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                    val c: Canvas = Canvas(prepared)
                    val scale: Float = Math.max(1920f / image.getWidth(), 1080f / image.getHeight())
                    val width: Float = image.getWidth() * scale
                    val height: Float = image.getHeight() * scale
                    val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    c.drawBitmap(
                        image,
                        null,
                        RectF(
                            (1920 - width) / 2,
                            (1080 - height) / 2,
                            (1920 + width) / 2,
                            (1080 + height) / 2,
                        ),
                        paint,
                    )
                    image.recycle()
                }
                theme = WallpaperTheme.fromImage(context, prepared, themeKey)
                BackdropStyle(context).apply(prepared)
            }
            return Prepared(prepared, theme, themeKey)
        }
    }
}
