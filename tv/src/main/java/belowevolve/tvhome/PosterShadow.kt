package belowevolve.tvhome

import android.graphics.*
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.ceil

/** Small precoloured textures; blur and tint run once on a worker, never per frame. */
internal object PosterShadow {
  val NAMES = arrayOf("Объёмная", "Снизу", "Свечение", "Без тени")
  const val None = 3
  const val DefaultY = 9

  data class Style(
    val kind: Int = 0,
    val wide: Boolean = false,
    val size: Int = 100,
    val blur: Int = 10,
  )

  class Mask(val image: ImageBitmap, val inset: Int)

  private data class Key(val colour: Int, val style: Style)

  private val outlines =
    object : LruCache<Style, Bitmap>(4 * 1024 * 1024) {
      override fun sizeOf(key: Style, value: Bitmap) = value.allocationByteCount
    }
  private val colours =
    object : LruCache<Key, Mask>(8 * 1024 * 1024) {
      override fun sizeOf(key: Key, value: Mask) = value.image.width * value.image.height * 4
    }

  fun inset(style: Style): Int {
    if (style.kind != 0) return if (style.wide) 32 else 24
    val growth = HomeLayout.PosterHeight * ((style.size - 100).coerceAtLeast(0) / 200f)
    val blur = style.blur * if (style.wide) 1.6f else 1f
    return ceil(growth + DefaultY + 2 * blur + 4).toInt()
  }

  fun cached(colour: Int, style: Style = Style()): Mask? = colours.get(Key(colour, style))

  @Synchronized
  fun load(colour: Int, style: Style = Style()): Mask {
    require(style.kind in 0 until None)
    cached(colour, style)?.let {
      return it
    }
    val inset = inset(style)
    val outline =
      outlines.get(style)
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
            when (style.kind) {
              0 -> {
                val scale = style.size / 100f
                rect.inset(
                  -HomeLayout.PosterWidth * (scale - 1f) / 2,
                  -HomeLayout.PosterHeight * (scale - 1f) / 2,
                )
                val radius = HomeLayout.CornerRadius * scale
                val blur = style.blur * if (style.wide) 1.6f else 1f
                paint.alpha = 45
                paint.maskFilter = BlurMaskFilter(minOf(6f, blur * .6f), BlurMaskFilter.Blur.NORMAL)
                canvas.drawRoundRect(rect, radius, radius, paint)
                paint.alpha = 220
                paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                rect.offset(0f, DefaultY.toFloat())
                canvas.drawRoundRect(rect, radius, radius, paint)
              }
              1 -> {
                paint.maskFilter =
                  BlurMaskFilter(if (style.wide) 14f else 9f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawOval(
                  RectF(rect.left + 8f, rect.bottom - 7f, rect.right - 8f, rect.bottom + 11f),
                  paint,
                )
              }
              else -> {
                paint.maskFilter =
                  BlurMaskFilter(if (style.wide) 18f else 12f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawRoundRect(rect, HomeLayout.CornerRadius, HomeLayout.CornerRadius, paint)
              }
            }
            outlines.put(style, bitmap)
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
    return Mask(bitmap.asImageBitmap(), inset).also { colours.put(Key(colour, style), it) }
  }
}
