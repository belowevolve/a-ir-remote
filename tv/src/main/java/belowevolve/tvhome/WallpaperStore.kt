package belowevolve.tvhome

import android.content.*
import android.graphics.*
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.*
import org.json.*

/* Bounded local cache of original-resolution Google Backdrop photographs. */
class WallpaperStore(c: Context) {
    private val dir: File
    private val index: File
    private val prefs: android.content.SharedPreferences

    init {
        dir = File(c.getFilesDir(), "landscapes")
        dir.mkdirs()
        index = File(dir, "index.json")
        prefs = c.getSharedPreferences("home", 0)
        if (!prefs.getBoolean("wallpaper_google_backdrop", false))
            prefs
                .edit()
                .putBoolean("wallpaper_google_backdrop", true)
                .putLong("wallpaper_next_fetch", 0)
                .apply()
    }

    private fun entries(): JSONArray {
        try {
            return JSONArray(
                String(java.nio.file.Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8),
            )
        } catch (e: Exception) {
            return JSONArray()
        }
    }

    class Photo(val file: File, val caption: String)

    fun photos(): ArrayList<Photo> {
        val photos: ArrayList<Photo> = ArrayList<Photo>()
        val items: JSONArray = entries()
        for (i: Int in 0 until items.length()) {
            val item: JSONObject? = items.optJSONObject(i)
            if (item == null) continue
            val file: File = File(dir, item.optString("file"))
            val caption: String = item.optString("copyright")
            if (file.isFile()) photos.add(Photo(file, caption))
        }
        return photos
    }

    fun selected(hour: Long): File? {
        val valid: ArrayList<Photo> = photos()
        if (valid.isEmpty()) return null
        val anchor: String = prefs.getString("wallpaper_anchor_file", "") ?: ""
        val offset: Int = prefs.getInt("wallpaper_offset", 0)
        for (i: Int in valid.indices) if (valid.get(i).file.getName() == anchor)
            return valid
                .get(
                    Math.floorMod(
                            i + hour - prefs.getLong("wallpaper_anchor_hour", hour) + offset,
                            valid.size,
                        )
                        .toInt(),
                )
                .file
        return valid.get(Math.floorMod(hour + offset, valid.size).toInt()).file
    }

    fun choose(photo: File): Boolean {
        synchronized(INDEX_LOCK) {
            if (!photo.isFile()) return false
            prefs
                .edit()
                .putString("wallpaper_anchor_file", photo.getName())
                .putLong("wallpaper_anchor_hour", System.currentTimeMillis() / 3600000)
                .putInt("wallpaper_offset", 0)
                .apply()
            return true
        }
    }

    @JvmOverloads
    fun refresh(force: Boolean = false): Boolean {
        val revision: Long = prefs.getLong("wallpaper_revision", 0)
        synchronized(REFRESH_LOCK) {
            // Reuse an update that finished while this request was waiting for the lock.
            if (force && prefs.getLong("wallpaper_revision", 0) != revision) return true
            val now: Long = System.currentTimeMillis()
            if (!force && now < prefs.getLong("wallpaper_next_fetch", 0)) return false
            prefs
                .edit()
                .putLong("wallpaper_next_fetch", now + 5 * 60000L)
                .putLong("wallpaper_last_attempt", now)
                .putString("wallpaper_status", "fetching")
                .apply()
            val updated: Boolean = collect(now)
            if (updated && !Thread.currentThread().isInterrupted())
                synchronized(INDEX_LOCK) {
                    val listed: MutableSet<String> = HashSet<String>()
                    val saved: JSONArray = entries()
                    for (i: Int in 0 until saved.length()) listed.add(
                        saved.optJSONObject(i).optString("file"),
                    )
                    val files: Array<File>? = dir.listFiles()
                    if (saved.length() > 0 && files != null)
                        for (f: File in files) if (
                            (f.getName().endsWith(".part") ||
                                (f.getName().endsWith(".jpg") && !listed.contains(f.getName())))
                        )
                            f.delete()
                }
            return updated
        }
    }

    private fun collect(now: Long): Boolean {
        val deadline: Long = android.os.SystemClock.elapsedRealtime() + 30000
        val old: JSONArray = entries()
        val result: JSONArray = JSONArray()
        val oldNames: MutableSet<String> = HashSet<String>()
        val keep: MutableSet<String> = HashSet<String>()
        for (i: Int in 0 until old.length()) oldNames.add(old.optJSONObject(i).optString("file"))
        var catalogueReceived: Boolean = false
        var transportError: Boolean = false
        try {
            val catalog: ByteArray = fetch(BackdropCatalog.FEED, 2 * 1024 * 1024, deadline)
            catalogueReceived = true
            val candidates: ArrayList<BackdropCatalog.Photo> =
                BackdropCatalog.parse(String(catalog, StandardCharsets.UTF_8))
            Collections.shuffle(candidates)
            for (photo: BackdropCatalog.Photo in candidates) {
                if (result.length() >= 8 || android.os.SystemClock.elapsedRealtime() >= deadline)
                    break
                val name: String =
                    "gbd-" +
                        UUID.nameUUIDFromBytes(photo.base.toByteArray(StandardCharsets.UTF_8)) +
                        ".jpg"
                if (oldNames.contains(name) || keep.contains(name)) continue
                try {
                    // Asking the CDN for 4K alone can upscale a small original. Check the original
                    // first.
                    val original: BitmapFactory.Options = originalBounds(photo.original(), deadline)
                    if (original.outWidth < 3840 || original.outHeight < 2160) continue
                    val data: ByteArray = fetch(photo.image(), 8 * 1024 * 1024, deadline)
                    val bounds: BitmapFactory.Options = BitmapFactory.Options()
                    bounds.inJustDecodeBounds = true
                    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                    if (bounds.outWidth < 3840 || bounds.outHeight < 2160) continue
                    val file: File = File(dir, name)
                    val temporary: File = File(dir, name + ".part")
                    FileOutputStream(temporary).use({ out -> out.write(data) })
                    if (!temporary.renameTo(file)) {
                        temporary.delete()
                        continue
                    }
                    result.put(
                        JSONObject()
                            .put("file", name)
                            .put("photo_checked", true)
                            .put("copyright", "Google Backdrop\n\u00a9 " + photo.author)
                            .put("source", "https://www.google.com/chromecast/backdrop/")
                            .put("artist", photo.author)
                            .put("license", "")
                            .put("original_width", original.outWidth)
                            .put("original_height", original.outHeight),
                    )
                    keep.add(name)
                } catch (unavailable: Exception) {
                    transportError = true
                    if (Thread.currentThread().isInterrupted()) return failed("cancelled")
                }
            }
        } catch (unavailable: Exception) {
            if (Thread.currentThread().isInterrupted()) return failed("cancelled")
            return failed(if (catalogueReceived) "catalog_changed" else "network_error")
        }

        if (result.length() == 0) {
            android.util.Log.w("AirHome", "WALLPAPERS no new photographs")
            return failed(if (transportError) "network_error" else "no_new_photos")
        }
        val newPhotos: Int = result.length()
        try {
            synchronized(INDEX_LOCK) {
                // Keep the user's anchor before the rest of the older cache.
                val anchor: String = prefs.getString("wallpaper_anchor_file", "") ?: ""
                for (pass: Int in 0..1) {
                    var i: Int = 0
                    while (i < old.length() && result.length() < 24) {
                        val item: JSONObject = old.getJSONObject(i)
                        val name: String = item.getString("file")
                        if (!name.startsWith("gbd-") || !item.optBoolean("photo_checked")) {
                            i++
                            continue
                        }
                        if (
                            ((pass == 0) != (name == anchor) ||
                                keep.contains(name) ||
                                !File(dir, name).isFile())
                        ) {
                            i++
                            continue
                        }
                        result.put(item)
                        keep.add(name)
                        i++
                    }
                }
                var bytes: Long = 0
                var reserved: Long =
                    if (anchor.startsWith("gbd-") && oldNames.contains(anchor))
                        File(dir, anchor).length()
                    else 0
                val bounded: JSONArray = JSONArray()
                keep.clear()
                for (i: Int in 0 until result.length()) {
                    val item: JSONObject = result.getJSONObject(i)
                    val f: File = File(dir, item.getString("file"))
                    val reserve: Long = if (f.getName() == anchor) 0 else reserved
                    if (bytes + f.length() + reserve > 64L * 1024 * 1024) continue
                    if (f.getName() == anchor) reserved = 0
                    bytes += f.length()
                    bounded.put(item)
                    keep.add(f.getName())
                }
                val temporary: File = File(dir, "index.part")
                java.nio.file.Files.write(
                    temporary.toPath(),
                    bounded.toString().toByteArray(StandardCharsets.UTF_8),
                )
                java.nio.file.Files.move(
                    temporary.toPath(),
                    index.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                prefs
                    .edit()
                    .putLong("wallpaper_last_success", now)
                    .putString("wallpaper_status", "ok")
                    .putLong("wallpaper_next_fetch", now + 24 * 3600000L)
                    .putLong("wallpaper_revision", prefs.getLong("wallpaper_revision", 0) + 1)
                    .apply()
                android.util.Log.i(
                    "AirHome",
                    "WALLPAPERS new=" +
                        newPhotos +
                        " cached=" +
                        bounded.length() +
                        " bytes=" +
                        bytes,
                )
                return true
            }
        } catch (unavailable: Exception) {
            return failed("cache_error")
        }
    }

    private fun failed(status: String): Boolean {
        prefs.edit().putString("wallpaper_status", status).apply()
        android.util.Log.w("AirHome", "WALLPAPERS status=" + status)
        return false
    }

    fun diagnostics(): JSONObject {
        val result: JSONObject = JSONObject()
        try {
            result
                .put("status", prefs.getString("wallpaper_status", "idle"))
                .put("attempt", prefs.getLong("wallpaper_last_attempt", 0))
                .put("success", prefs.getLong("wallpaper_last_success", 0))
                .put("cached", photos().size)
        } catch (impossible: JSONException) {
            throw AssertionError(impossible)
        }

        return result
    }

    fun failureMessage(): String {
        when (prefs.getString("wallpaper_status", "idle")) {
            "no_new_photos" -> return "Новых фотографий пока нет"
            "catalog_changed" ->
                return "Новые фото временно недоступны. Сохранённая подборка остаётся"
            "cache_error" -> return "Не удалось сохранить новые фото. Прежняя подборка остаётся"
            else -> return "Не удалось загрузить новые фото. Сохранённая подборка доступна"
        }
    }

    @Throws(Exception::class)
    private fun originalBounds(url: String, deadline: Long): BitmapFactory.Options {
        val c: HttpURLConnection = connection(url, deadline)
        try {
            c.getInputStream()
                .use(
                    { stream ->
                        object : FilterInputStream(stream) {
                                var remaining: Int = 256 * 1024

                                @Throws(IOException::class)
                                override fun skip(count: Long): Long {
                                    checkDeadline(deadline)
                                    val skipped: Long =
                                        `in`.skip(Math.min(Math.max(0, count), remaining.toLong()))
                                    remaining -= skipped.toInt()
                                    return skipped
                                }

                                @Throws(IOException::class)
                                override fun read(): Int {
                                    checkDeadline(deadline)
                                    if (remaining <= 0) return -1
                                    val value: Int = `in`.read()
                                    if (value >= 0) remaining--
                                    return value
                                }

                                @Throws(IOException::class)
                                override fun read(b: ByteArray, offset: Int, length: Int): Int {
                                    checkDeadline(deadline)
                                    if (length == 0) return 0
                                    if (remaining <= 0) return -1
                                    val count: Int =
                                        `in`.read(b, offset, Math.min(length, remaining))
                                    if (count > 0) remaining -= count
                                    return count
                                }
                            }
                            .use(
                                { bounded ->
                                    val bounds: BitmapFactory.Options = BitmapFactory.Options()
                                    bounds.inJustDecodeBounds = true
                                    BitmapFactory.decodeStream(bounded, null, bounds)
                                    return bounds
                                },
                            )
                    },
                )
        } finally {
            c.disconnect()
        }
    }

    @Throws(Exception::class)
    private fun connection(url: String, deadline: Long): HttpURLConnection {
        checkDeadline(deadline)
        val left: Long = deadline - android.os.SystemClock.elapsedRealtime()
        val c: HttpURLConnection = URL(url).openConnection() as HttpURLConnection
        c.setConnectTimeout(Math.max(1, Math.min(3000, left)).toInt())
        c.setReadTimeout(Math.max(1, Math.min(5000, left)).toInt())
        c.setRequestProperty("User-Agent", "AirHome/0.23 (personal Android TV wallpaper client)")
        return c
    }

    @Throws(Exception::class)
    private fun fetch(url: String, max: Int, deadline: Long): ByteArray {
        val c: HttpURLConnection = connection(url, deadline)
        try {
            c.getInputStream()
                .use(
                    { `in` ->
                        ByteArrayOutputStream()
                            .use(
                                { out ->
                                    val buffer: ByteArray = ByteArray(16384)
                                    var total: Int = 0
                                    while (true) {
                                        val count = `in`.read(buffer)
                                        if (count == -1) break
                                        if (
                                            (Thread.currentThread().isInterrupted() ||
                                                android.os.SystemClock.elapsedRealtime() >=
                                                    deadline)
                                        )
                                            throw InterruptedIOException()
                                        total += count
                                        if (total > max) throw IOException("Response too large")
                                        out.write(buffer, 0, count)
                                    }
                                    return out.toByteArray()
                                },
                            )
                    },
                )
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private val REFRESH_LOCK: Any = Any()
        private val INDEX_LOCK: Any = Any()

        @Throws(InterruptedIOException::class)
        private fun checkDeadline(deadline: Long) {
            if (
                (Thread.currentThread().isInterrupted() ||
                    android.os.SystemClock.elapsedRealtime() >= deadline)
            )
                throw InterruptedIOException()
        }
    }
}
