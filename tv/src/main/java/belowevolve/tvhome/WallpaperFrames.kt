package belowevolve.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.*
import java.util.UUID

/** One live frame in memory and three lossless prepared frames on disk. No original is modified. */
internal object WallpaperFrames {
  private data class Frame(
    val request: String,
    val render: String,
    val prepared: WallpaperController.Prepared,
  )

  @Volatile private var latest: Frame? = null

  fun peek(request: String): WallpaperController.Prepared? =
    latest
      ?.takeIf {
        it.request == request && it.prepared.image?.isRecycled == false
      }
      ?.prepared

  fun remember(request: String, render: String, prepared: WallpaperController.Prepared) {
    latest = Frame(request, render, prepared)
  }

  fun read(context: Context, request: String, render: String): WallpaperController.Prepared? {
    latest
      ?.takeIf { it.render == render && it.prepared.image?.isRecycled == false }
      ?.let {
        remember(request, render, it.prepared)
        return it.prepared
      }
    val file = file(context, render)
    if (!file.isFile) return null
    try {
      DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
        check(input.readUTF() == "AirHome.frame.1" && input.readUTF() == render)
        val imageKey = input.readUTF()
        val seed = input.readInt()
        val colors = IntArray(7) { input.readInt() }
        val image = BitmapFactory.decodeStream(input) ?: return null
        if (image.width != 1920 || image.height != 1080) {
          image.recycle()
          return null
        }
        image.setHasAlpha(false)
        image.prepareToDraw()
        val result =
          WallpaperController.Prepared(
            image,
            WallpaperTheme.restore(seed, colors, imageKey),
            imageKey,
          )
        remember(request, render, result)
        return result
      }
    } catch (_: IOException) {
      file.delete()
    } catch (_: IllegalStateException) {
      file.delete()
    }
    return null
  }

  fun write(context: Context, render: String, prepared: WallpaperController.Prepared) {
    val image = prepared.image ?: return
    val theme = prepared.theme ?: return
    if (Thread.currentThread().isInterrupted) return
    val target = file(context, render)
    if (target.isFile) return
    val temporary = File.createTempFile("frame-", ".part", target.parentFile)
    try {
      DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
        output.writeUTF("AirHome.frame.1")
        output.writeUTF(render)
        output.writeUTF(prepared.key)
        output.writeInt(theme.seed)
        theme.colors.forEach { output.writeInt(it) }
        check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
      }
      if (!Thread.currentThread().isInterrupted && temporary.renameTo(target)) {
        target.parentFile
          ?.listFiles { _, name -> name.endsWith(".frame") }
          ?.sortedByDescending { it.lastModified() }
          ?.drop(3)
          ?.forEach { it.delete() }
      }
    } catch (_: IOException) {
      // A disposable cache must not prevent displaying the live wallpaper.
    } finally {
      temporary.delete()
    }
  }

  private fun file(context: Context, key: String): File {
    val directory = File(context.cacheDir, "wallpaper-frames").apply { mkdirs() }
    return File(directory, UUID.nameUUIDFromBytes(key.toByteArray()).toString() + ".frame")
  }
}
