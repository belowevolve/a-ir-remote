package belowevolve.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/** A static wallpaper buffer lets the display compositor reuse it while the UI animates. */
internal class WallpaperSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
  private var image: Bitmap? = null
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val destination = Rect()

  init {
    holder.setFormat(PixelFormat.RGBX_8888)
    holder.addCallback(this)
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  fun show(bitmap: Bitmap) {
    if (image === bitmap) return
    image = bitmap
    render()
  }

  override fun surfaceCreated(holder: SurfaceHolder) = Unit

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    render()
  }

  override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

  private fun render() {
    val bitmap = image ?: return
    if (bitmap.isRecycled || !holder.surface.isValid || width == 0 || height == 0) return
    var canvas: Canvas? = null
    try {
      canvas = holder.lockHardwareCanvas()
      destination.set(0, 0, canvas.width, canvas.height)
      canvas.drawBitmap(bitmap, null, destination, paint)
    } catch (_: IllegalStateException) {
      // A surface replaced during a window transition is redrawn by surfaceChanged.
    } catch (_: Surface.OutOfResourcesException) {
      android.util.Log.w("AirHome", "Wallpaper surface unavailable")
    } finally {
      if (canvas != null) holder.unlockCanvasAndPost(canvas)
    }
  }
}
