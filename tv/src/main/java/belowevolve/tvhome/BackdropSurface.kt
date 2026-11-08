package belowevolve.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.SurfaceView

/** The wallpaper is submitted only when it changes, independently of focus animation frames. */
internal class BackdropSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
  private var image: Bitmap? = null
  private var background = android.graphics.Color.BLACK
  private var ready = false
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

  init {
    isFocusable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    holder.setFormat(PixelFormat.RGBX_8888)
    holder.addCallback(this)
  }

  fun show(bitmap: Bitmap?, color: Int) {
    if (image === bitmap && background == color) return
    image = bitmap
    background = color
    render()
  }

  override fun surfaceCreated(holder: SurfaceHolder) = Unit

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    ready = true
    render()
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    ready = false
  }

  private fun render() {
    if (!ready || !holder.surface.isValid) return
    val canvas = holder.lockCanvas() ?: return
    try {
      canvas.drawColor(background)
      val bitmap = image
      if (bitmap != null && !bitmap.isRecycled) {
        val scale =
          maxOf(canvas.width.toFloat() / bitmap.width, canvas.height.toFloat() / bitmap.height)
        val x = (canvas.width - bitmap.width * scale) / 2f
        val y = (canvas.height - bitmap.height * scale) / 2f
        canvas.drawBitmap(
          bitmap,
          null,
          RectF(x, y, x + bitmap.width * scale, y + bitmap.height * scale),
          paint,
        )
      }
    } finally {
      holder.unlockCanvasAndPost(canvas)
    }
  }
}
