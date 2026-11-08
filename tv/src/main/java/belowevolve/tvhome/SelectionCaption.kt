package belowevolve.tvhome

import android.text.TextUtils
import org.json.JSONObject

object SelectionCaption {
        fun metadataParts(image: String?): ArrayList<String> {
            val parts: ArrayList<String> = ArrayList<String>()
            val data: JSONObject? = PosterBadges.metadata(image)
            if (data == null) return parts
            val season: String = data.optString("season")
            val mark: String = data.optString("mark")
            val news: String = data.optString("news")
            if (!season.isEmpty())
                parts.add(
                    "Сезон " +
                        season.replaceFirst(("(?iu)^(?:сезон\\s*|с\\s*)(?=\\d)").toRegex(), ""),
                )
            if (!mark.isEmpty()) parts.add(mark)
            if (!news.isEmpty()) parts.add(news)
            return parts
        }

        fun description(image: String?): String {
            return TextUtils.join(" · ", metadataParts(image))
        }
}
