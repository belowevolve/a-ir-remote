package belowevolve.tvhome

import android.graphics.*
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Small precoloured textures; blur and tint run once on a worker, never per frame. */
internal object PosterShadow {
  class Mask(val image: ImageBitmap, val inset: Int)

  private val outlines = arrayOfNulls<Bitmap>(2)
  private val colours =
    object : LruCache<Pair<Int, Boolean>, Mask>(8 * 1024 * 1024) {
      override fun sizeOf(key: Pair<Int, Boolean>, value: Mask) =
        value.image.width * value.image.height * 4
    }

  fun cached(colour: Int, wide: Boolean): Mask? = colours.get(colour to wide)

  @Synchronized
  fun load(colour: Int, wide: Boolean): Mask {
    cached(colour, wide)?.let {
      return it
    }
    val index = if (wide) 1 else 0
    val inset = if (wide) 32 else 24
    val outline =
      outlines[index]
        ?: Bitmap.createBitmap(
            HomeLayout.PosterWidth + inset * 2,
            HomeLayout.PosterHeight + inset * 2,
            Bitmap.Config.ARGB_8888,
          )
          .also { bitmap ->
            Canvas(bitmap)
              .drawRoundRect(
                RectF(
                  inset.toFloat(),
                  inset.toFloat(),
                  (inset + HomeLayout.PosterWidth).toFloat(),
                  (inset + HomeLayout.PosterHeight).toFloat(),
                ),
                12f,
                12f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                  color = Color.WHITE
                  maskFilter = BlurMaskFilter(if (wide) 18f else 12f, BlurMaskFilter.Blur.NORMAL)
                },
              )
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
    return Mask(bitmap.asImageBitmap(), inset).also { colours.put(colour to wide, it) }
  }
}
