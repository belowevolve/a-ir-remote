package belowevolve.tvhome

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*

/* User-facing background modes. All photo treatment is baked on the wallpaper worker. */
class BackdropStyle(c: Context) {
  val bandCenter: Int
  val mode: Int
  val dim: Int
  val reach: Int
  val gradientDim: Int
  val mat: Int
  val matDim: Int
  val matOpacity: Int
  val blur: Int
  val blurDim: Int
  val blurHeight: Int
  val feather: Int

  init {
    val layout = HomeLayout(c)
    bandCenter = layout.posterTop + layout.posterHeight / 2
    val p: SharedPreferences = c.getSharedPreferences("appearance", 0)
    mode = get(p, "mode", 3, 0, 3)
    dim = get(p, "dim", 55, 0, 90)
    reach = get(p, "reach", 45, 5, 100)
    gradientDim = get(p, "gradient_dim", 75, 0, 100)
    mat = get(p, "mat", 0, 0, 2)
    matDim = get(p, "mat_dim", 25, 0, 90)
    matOpacity = get(p, "mat_opacity", 90, 20, 100)
    blur = get(p, "blur", 40, 0, 100)
    blurDim = get(p, "blur_dim", 40, 0, 90)
    blurHeight = get(p, "blur_height", 44, 10, 80)
    feather = get(p, "feather", 30, 0, 50)
  }

  fun fingerprint(): String {
    if (mode == 0) return "dim:" + dim
    if (mode == 1) return "gradient:" + reach + ":" + gradientDim
    if (mode == 2) return "shadow:" + matDim
    return "blur:" + blur + ":" + blurDim + ":" + blurHeight + ":" + feather + ":" + bandCenter
  }

  fun apply(image: Bitmap, checkCancelled: () -> Unit = {}) {
    val canvas: Canvas = Canvas(image)
    val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (mode == 0 || mode == 2) {
      paint.setColor(Color.argb(Math.round((if (mode == 0) dim else matDim) * 2.55f), 0, 0, 0))
      canvas.drawRect(0f, 0f, image.getWidth().toFloat(), image.getHeight().toFloat(), paint)
    } else if (mode == 1) {
      paint.setShader(
        LinearGradient(
          0f,
          image.getHeight() * (1 - reach / 100f),
          0f,
          image.getHeight().toFloat(),
          0,
          Color.argb(Math.round(gradientDim * 2.55f), 0, 0, 0),
          Shader.TileMode.CLAMP,
        )
      )
      canvas.drawRect(0f, 0f, image.getWidth().toFloat(), image.getHeight().toFloat(), paint)
    } else {
      val center: Float = image.getHeight() * bandCenter / 540f
      val half: Float = image.getHeight() * blurHeight / 200f
      val top: Float = Math.max(0f, center - half)
      val bottom: Float = Math.min(image.getHeight().toFloat(), center + half)
      val fade: Float = Math.max(.01f, feather / 100f)
      val stops: FloatArray = floatArrayOf(0f, fade, 1 - fade, 1f)
      val edge: Float = Math.max(.001f, feather / 250f)
      val sides: Shader =
        LinearGradient(
          0f,
          0f,
          image.getWidth().toFloat(),
          0f,
          intArrayOf(0, Color.BLACK, Color.BLACK, 0),
          floatArrayOf(0f, edge, 1 - edge, 1f),
          Shader.TileMode.CLAMP,
        )
      // Preserve the same visual radius while previews process one quarter of the pixels.
      val width = minOf(480, image.width / 4).coerceAtLeast(1)
      val height = minOf(270, image.height / 4).coerceAtLeast(1)
      val radius = Math.round(blur * .15f * width / 480f)
      if (radius > 0) {
        val small: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
          Canvas(small).drawBitmap(image, null, Rect(0, 0, width, height), paint)
          val pixels: IntArray = IntArray(width * height)
          val scratch: IntArray = IntArray(pixels.size)
          small.getPixels(pixels, 0, width, 0, 0, width, height)
          for (pass: Int in 0..2) {
            box(pixels, scratch, width, height, radius, true, checkCancelled)
            box(scratch, pixels, width, height, radius, false, checkCancelled)
          }
          small.setPixels(pixels, 0, width, 0, 0, width, height)
          val photo: BitmapShader =
            BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
          val matrix: Matrix = Matrix()
          matrix.setScale(
            image.getWidth() / width.toFloat(),
            image.getHeight() / height.toFloat(),
          )
          photo.setLocalMatrix(matrix)
          paint.setShader(
            ComposeShader(
              photo,
              LinearGradient(
                0f,
                top,
                0f,
                bottom,
                intArrayOf(0, Color.BLACK, Color.BLACK, 0),
                stops,
                Shader.TileMode.CLAMP,
              ),
              PorterDuff.Mode.DST_IN,
            )
          )
          paint.setShader(ComposeShader(paint.getShader(), sides, PorterDuff.Mode.DST_IN))
          canvas.drawRect(0f, top, image.getWidth().toFloat(), bottom, paint)
        } finally {
          small.recycle()
        }
      }
      val shade: Int = Color.argb(Math.round(blurDim * 2.55f), 0, 0, 0)
      paint.setShader(
        LinearGradient(
          0f,
          top,
          0f,
          bottom,
          intArrayOf(0, shade, shade, 0),
          stops,
          Shader.TileMode.CLAMP,
        )
      )
      paint.setShader(ComposeShader(paint.getShader(), sides, PorterDuff.Mode.DST_IN))
      canvas.drawRect(0f, top, image.getWidth().toFloat(), bottom, paint)
    }
  }

  companion object {
    val NAMES: Array<String> =
      arrayOf<String>("Тихий фон", "Нижний градиент", "Тени карточек", "Размытая полоса")

    private fun get(p: SharedPreferences, key: String, value: Int, min: Int, max: Int): Int {
      return Math.max(min, Math.min(max, p.getInt(key, value)))
    }

    private fun box(
      source: IntArray,
      target: IntArray,
      width: Int,
      height: Int,
      radius: Int,
      horizontal: Boolean,
      checkCancelled: () -> Unit,
    ) {
      val length: Int = if (horizontal) width else height
      val lines: Int = if (horizontal) height else width
      val step: Int = if (horizontal) 1 else width
      val divisor: Int = radius * 2 + 1
      for (line: Int in 0 until lines) {
        if (line % 32 == 0) checkCancelled()
        val base: Int = if (horizontal) line * width else line
        var red: Int = 0
        var green: Int = 0
        var blue: Int = 0
        for (k: Int in -radius..radius) {
          val color: Int = source[base + Math.max(0, Math.min(length - 1, k)) * step]
          red += (color shr 16) and 255
          green += (color shr 8) and 255
          blue += color and 255
        }
        for (x: Int in 0 until length) {
          target[base + x * step] =
            -0x1000000 or (red / divisor shl 16) or (green / divisor shl 8) or blue / divisor
          val leaving: Int = source[base + Math.max(0, x - radius) * step]
          val entering: Int = source[base + Math.min(length - 1, x + radius + 1) * step]
          red += ((entering shr 16) and 255) - ((leaving shr 16) and 255)
          green += ((entering shr 8) and 255) - ((leaving shr 8) and 255)
          blue += (entering and 255) - (leaving and 255)
        }
      }
    }
  }
}
