package belowevolve.airremote

/** Relative, bounded offsets. The verb supplies direction; an omitted duration is ten seconds. */
internal object SpokenSeek {
    private const val DEFAULT_SECONDS = 10
    private fun words(value: Int): String = SpokenVolume.words(value)
        .replace(Regex("один$"), "одну").replace(Regex("два$"), "две")

    private fun unit(value: Int, minutes: Boolean): String {
        val form = when {
            value % 100 in 11..14 -> 2
            value % 10 == 1 -> 0
            value % 10 in 2..4 -> 1
            else -> 2
        }
        return (if (minutes) listOf("минуту", "минуты", "минут")
            else listOf("секунду", "секунды", "секунд"))[form]
    }

    val recognitionPhrases: List<String> by lazy {
        // Vosk estimates a bigram language model from these examples. Enumerating
        // every optional prefix/direction overweights the duration words and makes
        // the existing volume commands wait longer for an utterance endpoint.
        // Cover each transition, without repeating the entire cross product.
        buildList {
            addAll(listOf("эйр перемотай", "эйр перемотай назад", "эйр перемотай вперёд",
                "эйр отмотай", "эйр отмотай назад"))
            for (minutes in listOf(true, false)) {
                for (value in 1..100)
                    add("эйр перемотай ${words(value)} ${unit(value, minutes)}")
                for (value in (1..20) + (30..90 step 10) + listOf(100))
                    add("эйр перемотай на ${words(value)} ${unit(value, minutes)}")
                for (value in listOf(1, 2, 5)) for (direction in listOf("назад", "вперёд"))
                    add("эйр перемотай ${words(value)} ${unit(value, minutes)} $direction")
                for (value in (1..20) + (30..90 step 10) + listOf(100))
                    add("эйр отмотай ${words(value)} ${unit(value, minutes)}")
                for (value in listOf(1, 2, 5))
                    add("эйр отмотай на ${words(value)} ${unit(value, minutes)} назад")
            }
        }
    }

    fun parse(text: String): Int? {
        val prefix = listOf("эйр перемотай", "эйр отмотай")
            .firstOrNull { text == it || text.startsWith("$it ") } ?: return null
        val backwards = prefix.endsWith("отмотай")
        var body = text.removePrefix(prefix).trim()
        if (backwards && (body == "вперед" || body.endsWith(" вперед"))) return null
        val sign = if (backwards || body == "назад" || body.endsWith(" назад")) -1 else 1
        if (body.isEmpty() || body in listOf("назад", "вперед")) return sign * DEFAULT_SECONDS
        body = body.removePrefix("на ")
        body = when {
            body.endsWith(" назад") -> body.removeSuffix(" назад")
            body.endsWith(" вперед") -> body.removeSuffix(" вперед")
            else -> body
        }
        val parts = body.split(' ')
        val scale = when (parts.lastOrNull()) {
            "минуту", "минуты", "минут" -> 60
            "секунду", "секунды", "секунд" -> 1
            else -> return null
        }
        val number = parts.dropLast(1).joinToString(" ")
            .replace(Regex("одну$"), "один").replace(Regex("две$"), "два")
        val value = SpokenVolume.parse(number)?.takeIf { it > 0 } ?: return null
        return sign * value * scale
    }

    fun transcript(seconds: Int): String {
        val absolute = kotlin.math.abs(seconds)
        val minutes = absolute % 60 == 0
        val value = if (minutes) absolute / 60 else absolute
        val number = if (value <= 100) words(value) else value.toString()
        return "эйр ${if (seconds < 0) "отмотай" else "перемотай"} $number ${unit(value, minutes)}"
    }
}
