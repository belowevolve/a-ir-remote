package belowevolve.tvhome

import java.io.IOException
import java.net.URI
import java.util.ArrayList
import java.util.HashSet
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONException

/* Anonymous Google Backdrop photo feed. A changed contract fails back to the local cache. */
object BackdropCatalog {
    val FEED: String = "https://clients3.google.com/cast/chromecast/home?iv=atv"
    private val STATE: Pattern =
        Pattern.compile("constant\\(['\"]initialStateJson['\"],\\s*JSON\\.parse\\((['\"])")

    class Photo(val base: String, val author: String) {

        fun original(): String {
            return base + "=s0"
        }

        fun image(): String {
            return base + "=w3840-h2160-p-k-no-nd-mv"
        }
    }

    @Throws(IOException::class, JSONException::class)
    fun parse(html: String): ArrayList<Photo> {
        val match: Matcher = STATE.matcher(html)
        if (!match.find()) throw IOException("Backdrop state missing")
        val json: String = string(html, match.end(), requireNotNull(match.group(1))[0])
        val topics: JSONArray = JSONArray(json).getJSONArray(0)
        val result: ArrayList<Photo> = ArrayList<Photo>()
        val seen: HashSet<String> = HashSet<String>()
        for (i: Int in 0 until topics.length()) {
            val row: JSONArray? = topics.optJSONArray(i)
            if (row == null) continue
            val url: String = row.optString(0, "")
            var author: String = row.optString(1, "").trim({ it <= ' ' })
            // The anonymous feed includes a Google Home customization promotion, not a photograph.
            if (author.isEmpty() || row.optString(9).contains("google.com/chromecast/backdrop"))
                continue
            try {
                val uri: URI = URI(url)
                val host: String? = uri.getHost()
                if (
                    ("https" != uri.getScheme() ||
                        host == null ||
                        !host.endsWith(".googleusercontent.com") ||
                        uri.getRawQuery() != null ||
                        uri.getRawFragment() != null ||
                        uri.getUserInfo() != null)
                )
                    continue
                val base: String = url.replaceFirst(("=(?:[whs]\\d+)[^/?#]*$").toRegex(), "")
                if (!seen.add(base)) continue
                author = author.replace(("\\s+").toRegex(), " ")
                if (author.length > 160) author = author.substring(0, 160)
                result.add(Photo(base, author))
            } catch (invalid: java.net.URISyntaxException) {
                // An invalid record must not discard the remaining usable photographs.
            }
        }
        if (result.isEmpty()) throw IOException("Backdrop has no usable photos")
        return result
    }

    // Decode the JS string once; JSON then decodes nested escapes. Avoid a recursive regex over
    // the large payload: Android's regex stack can overflow on a whole catalogue string.
    @Throws(IOException::class)
    private fun string(source: String, start: Int, quote: Char): String {
        val decoded: StringBuilder = StringBuilder()
        var i: Int = start
        while (i < source.length) {
            val c: Char = source.get(i)
            if (c == quote) return decoded.toString()
            if (c != '\\') {
                decoded.append(c)
                i++
                continue
            }
            if (++i == source.length) break
            val escape: Char = source.get(i)
            when (escape) {
                'x',
                'u' -> {
                    val length: Int = if (escape == 'x') 2 else 4
                    if (i + length >= source.length) throw IOException("Truncated state escape")
                    try {
                        decoded.append(
                            Integer.parseInt(source.substring(i + 1, i + length + 1), 16).toChar(),
                        )
                    } catch (invalid: NumberFormatException) {
                        throw IOException("Invalid state escape", invalid)
                    }

                    i += length
                }
                '\\',
                '/',
                '\'',
                '"' -> decoded.append(escape)
                'n' -> decoded.append('\n')
                'r' -> decoded.append('\r')
                't' -> decoded.append('\t')
                'b' -> decoded.append('\b')
                'f' -> decoded.append('\u000C')
                else -> throw IOException("Unknown state escape")
            }
            i++
        }
        throw IOException("Unterminated state")
    }
}
