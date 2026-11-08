package belowevolve.tvhome

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.*

/* Bounded original-artwork cache and focused-card-sized decoding. */
class PosterStore(context: Context) {
    private val context: Context

    init {
        this.context = context.getApplicationContext()
    }

    private fun d(value: Float): Int {
        return Ui.dp(context, value)
    }

    data class CardArtwork(val bitmap: Bitmap?, val separateBadges: Boolean)

    /** Read clean artwork from the producer's local cache before using the remote URL. */
    internal fun loadCard(uri: String, read: (String) -> Bitmap? = ::load): CardArtwork {
        val metadata = PosterBadges.metadata(uri) ?: return CardArtwork(read(uri), false)
        val original = metadata.getString("poster")
        // A distinct path is essential: older TvArt ignores query flags on a legacy poster path.
        val local =
            Uri.Builder()
                .scheme("content")
                .authority("mov.film.tvart")
                .path("clean")
                .appendQueryParameter("poster", original)
                .build()
                .toString()
        val clean = read(local) ?: read(original)
        return if (clean != null) CardArtwork(clean, true) else CardArtwork(read(uri), false)
    }

    fun load(uri: String): Bitmap? {
        var temporary: File? = null
        try {
            val dir: File = File(context.getFilesDir(), "posters")
            dir.mkdirs()
            val name: String =
                UUID.nameUUIDFromBytes(uri.toByteArray(StandardCharsets.UTF_8)).toString() + ".img"
            val f: File = File(dir, name)
            if (Thread.currentThread().isInterrupted()) return null
            if (f.exists()) {
                val cached: Bitmap? = decode(f)
                if (cached != null) return cached
                f.delete()
            }
            // Different cards can request the same artwork concurrently. Never share a partial
            // file.
            temporary = File.createTempFile(name, ".part", dir)
            (openImage(uri) ?: throw IOException("Image unavailable")).use(
                { `in` ->
                    FileOutputStream(temporary)
                        .use(
                            { out ->
                                val buf: ByteArray = ByteArray(16384)
                                var total: Int = 0
                                while (true) {
                                    val read = `in`.read(buf)
                                    if (read == -1) break
                                    if (Thread.currentThread().isInterrupted())
                                        throw InterruptedIOException()
                                    total += read
                                    if (total > 5 * 1024 * 1024)
                                        throw IOException("Image too large")
                                    out.write(buf, 0, read)
                                }
                            },
                        )
                },
            )
            val image: Bitmap? = decode(temporary)
            if (image == null) return null
            if (!temporary!!.renameTo(f)) {
                image.recycle()
                throw IOException("Cannot save poster")
            }
            val files: Array<File>? = dir.listFiles({ folder, entry -> entry.endsWith(".img") })
            if (files != null && files.size > 64) {
                files.sortBy { it.lastModified() }
                for (i: Int in 0 until files.size - 48) files[i].delete()
            }
            return image
        } catch (e: Exception) {
            android.util.Log.w("AirHome", "Poster unavailable: " + e.javaClass.getSimpleName())
            return null
        } finally {
            if (temporary != null) temporary.delete()
        }
    }

    private fun decode(file: File?): Bitmap? {
        val bounds: BitmapFactory.Options = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file!!.getPath(), bounds)
        val options: BitmapFactory.Options = BitmapFactory.Options()
        var sample: Int = 1
        // Decode enough pixels for the focused card, without uploading oversized posters.
        while (
            (bounds.outWidth / (sample * 2) >= d(104 * CardMotion.MaxScale) &&
                bounds.outHeight / (sample * 2) >= d(156 * CardMotion.MaxScale))
        ) sample *= 2
        options.inSampleSize = sample
        return BitmapFactory.decodeFile(file.getPath(), options)
    }

    @Throws(Exception::class)
    private fun openImage(uri: String): InputStream? {
        if (uri.startsWith("content:") || uri.startsWith("android.resource:"))
            return context.getContentResolver().openInputStream(Uri.parse(uri))
        val c: URLConnection = URL(uri).openConnection()
        c.setConnectTimeout(6000)
        c.setReadTimeout(8000)
        return c.getInputStream()
    }
}
