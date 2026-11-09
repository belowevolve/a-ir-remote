package belowevolve.tvhome

import android.content.Context
import android.graphics.*
import android.os.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
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
  private var render: Future<*>? = null
  private var visible: Boolean = false
  @Volatile private var closed: Boolean = false
  @Volatile private var generation: Int = 0
  private var hourShown: Long = -1
  private var styleShown: String = ""
  private val refresh: Runnable = Runnable({ this.refreshNetwork() })

  private fun refreshNetwork() {
    if (!visible || closed) return
    network.execute({
      if (WallpaperStore(context).refresh())
        main.post({
          if (closed) return@post
          invalidate()
          if (visible) update()
        })
    })
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
    if (render?.cancel(true) == true) {
      generation++
      hourShown = -1
    }
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
    render?.cancel(true)
    val identity = requestKey(hour, style)
    WallpaperFrames.peek(identity)?.let {
      it.theme?.save(context, it.key)
      listener?.accept(it.image!!, it.theme)
      return
    }
    render =
      decode.submit(
        Runnable {
          if (request != generation || closed) return@Runnable
          val store: WallpaperStore = WallpaperStore(context)
          val selected: File? = store.selected(hour)
          val renderKey =
            context.filesDir.path +
              ":" +
              (selected?.let { it.name + ":" + it.lastModified() } ?: FallbackThemeKey) +
              ":" +
              style
          val saved = WallpaperFrames.read(context, identity, renderKey)
          val result: Prepared = saved ?: prepare(context, selected)
          val ready: Bitmap? = result.image
          val readyTheme: WallpaperTheme? = result.theme
          val themeKey: String = result.key
          if (request != generation || closed) return@Runnable
          if (ready != null) WallpaperFrames.remember(identity, renderKey, result)
          main.post({
            if (closed || request != generation) {
              return@post
            }
            if (ready != null) {
              if (readyTheme != null) readyTheme.save(context, themeKey)
              listener!!.accept(ready, readyTheme)
            }
          })
          if (saved == null && request == generation && !closed)
            WallpaperFrames.write(context, renderKey, result)
        }
      )
  }

  fun cached(): Prepared? =
    WallpaperFrames.peek(
      requestKey(System.currentTimeMillis() / 3600000, BackdropStyle(context).fingerprint())
    )

  private fun requestKey(hour: Long, style: String): String {
    val p = context.getSharedPreferences("home", 0)
    return context.filesDir.path +
      ":" +
      hour +
      ":" +
      style +
      ":" +
      p.getString("wallpaper_anchor_file", "") +
      ":" +
      p.getLong("wallpaper_anchor_hour", 0) +
      ":" +
      p.getInt("wallpaper_offset", 0) +
      ":" +
      p.getLong("wallpaper_revision", 0)
  }

  class Prepared(val image: Bitmap?, val theme: WallpaperTheme?, val key: String)

  companion object {
    // Keep the persisted theme/cache identity while giving the bundled resource a clear name.
    private const val FallbackThemeKey = "landscape_02"

    fun prepare(
      context: Context,
      selected: File?,
      width: Int = 1920,
      height: Int = 1080,
      checkCancelled: () -> Unit = {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
      },
    ): Prepared {
      val themeKey: String =
        if (selected == null) FallbackThemeKey
        else selected.getName() + ":" + selected.lastModified()
      var source: Bitmap? = null
      var prepared: Bitmap? = null
      val preview =
        if (selected != null && width < 1920) {
          val directory = File(context.cacheDir, "wallpaper-previews").apply { mkdirs() }
          val name = UUID.nameUUIDFromBytes("$themeKey:$width:$height".toByteArray()).toString()
          File(directory, "$name.jpg")
        } else null
      val input = preview?.takeIf { it.isFile } ?: selected
      Trace.beginSection("AirHome.wallpaper.${width}x${height}")
      try {
        checkCancelled()
        val bounds =
          BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
          }
        if (input != null) BitmapFactory.decodeFile(input.path, bounds)
        else BitmapFactory.decodeResource(context.resources, R.raw.fallback_wallpaper, bounds)
        val options =
          BitmapFactory.Options().apply {
            inScaled = false
            inMutable = true
            inSampleSize = 1
          }
        while (
          bounds.outWidth / (options.inSampleSize * 2) >= width &&
            bounds.outHeight / (options.inSampleSize * 2) >= height
        ) options.inSampleSize *= 2
        source =
          if (input != null) BitmapFactory.decodeFile(input.path, options)
          else BitmapFactory.decodeResource(context.resources, R.raw.fallback_wallpaper, options)
        val image = source ?: return Prepared(null, null, themeKey)
        prepared = image
        if (image.width != width || image.height != height) {
          prepared = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
          val scale = maxOf(width.toFloat() / image.width, height.toFloat() / image.height)
          val scaledWidth = image.width * scale
          val scaledHeight = image.height * scale
          Canvas(prepared)
            .drawBitmap(
              image,
              null,
              RectF(
                (width - scaledWidth) / 2,
                (height - scaledHeight) / 2,
                (width + scaledWidth) / 2,
                (height + scaledHeight) / 2,
              ),
              Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        }
        // Cache the untreated small image, so styles can change without decoding 4K again.
        if (preview != null && input != preview) savePreview(prepared, preview)
        checkCancelled()
        val theme = WallpaperTheme.fromImage(context, prepared, themeKey)
        checkCancelled()
        BackdropStyle(context).apply(prepared, checkCancelled)
        checkCancelled()
        prepared.setHasAlpha(false)
        prepared.prepareToDraw()
        return Prepared(prepared, theme, themeKey)
      } catch (error: Throwable) {
        prepared?.recycle()
        throw error
      } finally {
        if (source !== prepared) source?.recycle()
        Trace.endSection()
      }
    }

    private fun savePreview(image: Bitmap, file: File) {
      var temporary: File? = null
      try {
        temporary = File.createTempFile("preview-", ".part", file.parentFile)
        val saved =
          FileOutputStream(temporary).use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (saved && temporary.renameTo(file)) {
          val cached = file.parentFile?.listFiles { _, name -> name.endsWith(".jpg") }.orEmpty()
          cached.sortedByDescending { it.lastModified() }.drop(24).forEach { it.delete() }
        }
      } catch (_: java.io.IOException) {
        // A disposable preview cache must not prevent choosing an original wallpaper.
      } finally {
        temporary?.delete()
      }
    }
  }
}
