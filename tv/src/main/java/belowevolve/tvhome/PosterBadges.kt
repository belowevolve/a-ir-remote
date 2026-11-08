package belowevolve.tvhome

import android.net.Uri
import org.json.JSONObject

/** Optional read-only film.mov poster metadata, shared by Compose cards and the cache. */
object PosterBadges {
        fun metadata(image: String?): JSONObject? {
            if (image == null) return null
            try {
                val uri: Uri = Uri.parse(image)
                if ("mov.film.tvart" != uri.getAuthority()) return null
                val encoded: String? = uri.getQueryParameter("airhome_badges")
                if (encoded == null || encoded.length > 8192) return null
                val metadata: JSONObject = JSONObject(encoded)
                val original: Uri = Uri.parse(metadata.optString("poster"))
                if (
                    (metadata.optInt("v") != 1 ||
                        !("https" == original.getScheme() || "http" == original.getScheme()))
                )
                    return null
                return metadata
            } catch (unsupported: Exception) {
                return null
            }
        }

}
