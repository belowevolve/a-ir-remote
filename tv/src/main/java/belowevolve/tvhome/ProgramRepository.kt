package belowevolve.tvhome

import android.content.Context
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import java.io.File
import java.util.*
import java.util.function.Function

/* film.mov channel queries and on-disk snapshots; the Activity owns rendering and focus. */
class ProgramRepository(private val context: Context, private val query: Function<Uri, Cursor?>) {

    constructor(
        context: Context,
    ) : this(
        context.getApplicationContext(),
        { uri -> context.getContentResolver().query(uri, null, null, null, null) },
    ) {}

    class Program {
        var title: String = ""
        var image: String = ""
        var intent: String = ""
        var time: Long = 0
        var weight: Long = 0
    }

    private fun get(c: Cursor?, key: String): String? {
        val i: Int = c!!.getColumnIndex(key)
        return if (i < 0) "" else c.getString(i)
    }

    private fun number(c: Cursor?, key: String): Long {
        val i: Int = c!!.getColumnIndex(key)
        return if (i < 0) 0 else c.getLong(i)
    }

    fun cached(): ArrayList<Program> {
        val cached: ArrayList<Program> = ArrayList<Program>()
        try {
            val data: String =
                String(
                    java.nio.file.Files.readAllBytes(
                        File(context.getFilesDir(), "programs.json").toPath(),
                    ),
                    java.nio.charset.StandardCharsets.UTF_8,
                )
            val items: org.json.JSONArray = org.json.JSONArray(data)
            for (i: Int in 0 until items.length()) {
                val j: org.json.JSONObject = items.getJSONObject(i)
                val p: Program = Program()
                p.title = j.getString("title")
                p.image = j.getString("image")
                p.intent = j.getString("intent")
                cached.add(p)
            }
        } catch (ignored: Exception) {}

        return cached
    }

    private fun save(programs: ArrayList<Program>) {
        try {
            val items: org.json.JSONArray = org.json.JSONArray()
            for (p: Program in programs) items.put(
                org.json
                    .JSONObject()
                    .put("title", p.title)
                    .put("image", p.image)
                    .put("intent", p.intent),
            )
            val tmp: File = File(context.getFilesDir(), "programs.part")
            java.nio.file.Files.write(
                tmp.toPath(),
                items.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8),
            )
            java.nio.file.Files.move(
                tmp.toPath(),
                File(context.getFilesDir(), "programs.json").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (ignored: Exception) {}
    }

    /* Null means the provider failed; keep the last complete shelf until a later refresh. */
    fun load(): ArrayList<Program>? {
        val out: ArrayList<Program> = ArrayList<Program>()
        val continuing: HashSet<Long> = HashSet<Long>()
        try {
            query
                .apply(TvContract.Channels.CONTENT_URI)
                .use(
                    { channels ->
                        if (channels == null) return null
                        while (channels.moveToNext()) {
                            val pkg: String? = get(channels, "package_name")
                            val label: String? = get(channels, "display_name")
                            if (
                                ("mov.film" == pkg &&
                                    label != null &&
                                    label.lowercase(Locale.ROOT).contains("продолж"))
                            )
                                continuing.add(number(channels, "_id"))
                        }
                    },
                )
        } catch (e: Exception) {
            android.util.Log.w("AirHome", "Channels query", e)
            return null
        }

        for (source: Uri in
            arrayOf<Uri>(
                TvContract.WatchNextPrograms.CONTENT_URI,
                Uri.parse("content://android.media.tv/preview_program"),
            )) {
            try {
                query
                    .apply(source)
                    .use(
                        { c ->
                            if (c == null) return null
                            while (c.moveToNext()) {
                                if ("mov.film" != get(c, "package_name")) continue
                                if (
                                    (source.getLastPathSegment() == "preview_program" &&
                                        !continuing.isEmpty() &&
                                        !continuing.contains(number(c, "channel_id")))
                                )
                                    continue
                                val p: Program = Program()
                                p.title = get(c, "title") ?: ""
                                p.image = get(c, "poster_art_uri") ?: continue
                                p.intent = get(c, "intent_uri") ?: continue
                                p.time = number(c, "last_engagement_time_utc_millis")
                                p.weight = number(c, "weight")
                                out.add(p)
                            }
                        },
                    )
            } catch (e: Exception) {
                android.util.Log.w("AirHome", "TV programs query", e)
                return null
            }
        }
        out.sortWith(
            { a, b ->
                if (a.time != b.time) java.lang.Long.compare(b.time, a.time)
                else java.lang.Long.compare(b.weight, a.weight)
            },
        )
        android.util.Log.i("AirHome", "film.mov count=" + out.size)
        save(out)
        return out
    }
}
