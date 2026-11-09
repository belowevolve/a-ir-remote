package belowevolve.tvhome

import belowevolve.shared.design.PosterBadgeSpec
import org.json.JSONObject

object SelectionCaption {
    fun metadataParts(image: String?): ArrayList<String> {
        val parts: ArrayList<String> = ArrayList<String>()
        val data: JSONObject? = PosterBadges.metadata(image)
        if (data == null) return parts
        val season = PosterBadgeSpec.seasonLabel(data.optString("season"))
        val mark = PosterBadgeSpec.episodeLabel(data.optString("mark"))
        val news = data.optString("news").trim()
        if (!season.isEmpty()) parts.add(season)
        if (!mark.isEmpty()) parts.add(mark)
        if (!news.isEmpty()) parts.add(news)
        return parts
    }

    fun description(image: String?): String {
        return metadataParts(image).joinToString(" · ")
    }
}
