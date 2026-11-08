package belowevolve.tvhome

import android.graphics.*
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Small precoloured textures; blur and tint run once on a worker, never per frame. */
internal object PosterShadow {
  val NAMES = arrayOf("Объёмная", "Снизу", "Свечение", "Без тени")
  const val None = 3

  class Mask(val image: ImageBitmap, val inset: Int)

  private data class Key(val colour: Int, val wide: Boolean, val kind: Int)

  private val outlines = arrayOfNulls<Bitmap>(6)
  private val colours =
    object : LruCache<Key, Mask>(8 * 1024 * 1024) {
      override fun sizeOf(key: Key, value: Mask) = value.image.width * value.image.height * 4
    }

  fun cached(colour: Int, wide: Boolean, kind: Int = 0): Mask? =
    colours.get(Key(colour, wide, kind))

  @Synchronized
  fun load(colour: Int, wide: Boolean, kind: Int = 0): Mask {
    require(kind in 0 until None)
    cached(colour, wide, kind)?.let {
      return it
    }
    val index = kind * 2 + if (wide) 1 else 0
    val inset = if (wide) 32 else 24
    val outline =
      outlines[index]
        ?: Bitmap.createBitmap(
            HomeLayout.PosterWidth + inset * 2,
            HomeLayout.PosterHeight + inset * 2,
            Bitmap.Config.ARGB_8888,
          )
          .also { bitmap ->
            val canvas = Canvas(bitmap)
            val rect =
              RectF(
                inset.toFloat(),
                inset.toFloat(),
                (inset + HomeLayout.PosterWidth).toFloat(),
                (inset + HomeLayout.PosterHeight).toFloat(),
              )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            when (kind) {
              0 -> {
                paint.alpha = 45
                paint.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawRoundRect(rect, 12f, 12f, paint)
                paint.alpha = 220
                paint.maskFilter =
                  BlurMaskFilter(if (wide) 16f else 10f, BlurMaskFilter.Blur.NORMAL)
                rect.offset(0f, 9f)
                canvas.drawRoundRect(rect, 12f, 12f, paint)
              }
              1 -> {
                paint.maskFilter = BlurMaskFilter(if (wide) 14f else 9f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawOval(
                  RectF(rect.left + 8f, rect.bottom - 7f, rect.right - 8f, rect.bottom + 11f),
                  paint,
                )
              }
              else -> {
                paint.maskFilter =
                  BlurMaskFilter(if (wide) 18f else 12f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawRoundRect(rect, 12f, 12f, paint)
              }
            }
            outlines[index] = bitmap
          }
    val bitmap = Bitmap.createBitmap(outline.width, outline.height, Bitmap.Config.ARGB_8888)
    Canvas(bitmap)
      .drawBitmap(
        outline,
        0f,
        0f,
        Paint().apply {
          colorFilter = PorterDuffColorFilter(colour, PorterDuff.Mode.SRC_IN)
        },
      )
    bitmap.prepareToDraw()
    return Mask(bitmap.asImageBitmap(), inset).also { colours.put(Key(colour, wide, kind), it) }
  }
}
