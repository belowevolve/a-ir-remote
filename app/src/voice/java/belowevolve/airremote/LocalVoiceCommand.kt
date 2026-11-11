package belowevolve.airremote

/** A complete addressed phrase is required; ordinary TV dialogue must not act as a command. */
internal enum class LocalVoiceCommand {
    TV_TOGGLE, TV_ON, TV_OFF, FILM, YOUTUBE, PC,
    OK, BACK, PAUSE, PLAY, NEXT, PREVIOUS, SEEK, VOLUME_UP, VOLUME_DOWN, VOLUME_SET, STOP_LISTENING;

    companion object {
        val phrases = linkedMapOf(
            "эйр тв" to TV_TOGGLE,
            "эйр тэ вэ" to TV_TOGGLE,
            "эйр фильм" to FILM,
            "эйр ютуб" to YOUTUBE,
            "эйр ютюб" to YOUTUBE,
            "эйр ютьюб" to YOUTUBE,
            "эйр комп" to PC,
            "эйр компьютер" to PC,
            "эйр включи телевизор" to TV_ON,
            "эйр включи тв" to TV_ON,
            "эйр выключи телевизор" to TV_OFF,
            "эйр выключи тв" to TV_OFF,
            "эйр ок" to OK,
            "эйр окей" to OK,
            "эйр назад" to BACK,
            "эйр стоп" to PAUSE,
            "эйр пауза" to PAUSE,
            "эйр плей" to PLAY,
            "эйр продолжай" to PLAY,
            "эйр дальше" to PLAY,
            "эйр следующее" to NEXT,
            "эйр следующий" to NEXT,
            "эйр скип" to NEXT,
            "эйр прошлое" to PREVIOUS,
            "эйр предыдущее" to PREVIOUS,
            "эйр предыдущий" to PREVIOUS,
            "эйр громче" to VOLUME_UP,
            "эйр тише" to VOLUME_DOWN,
            "эйр хватит слушать" to STOP_LISTENING,
        )

        val rejectedPhrases = listOf(
            "эйр", "эйр не включай телевизор", "эйр не фильм", "эйр не ютуб", "эйр не комп",
            "эйр пока", "эйр тв не надо", "эйр фильм не надо", "эйр ютуб не надо", "эйр комп не надо",
            "пульт включи телевизор", "включи телевизор", "посмотрим фильм", "[unk]",
            "эйр не стоп", "эйр не пауза", "эйр не продолжай", "эйр не громче", "эйр не тише",
            "эйр не скип", "эйр скип не надо",
            "эйр громкость", "эйр громкость сто один", "эйр громкость минус один",
            "эйр не назад", "эйр назад не надо",
            "эйр не перемотай", "эйр перемотай не надо",
            "эйр не отмотай", "эйр отмотай не надо",
            "эйр громче два не надо", "эйр тише три не надо",
            "эйр громче минус два", "эйр тише ноль", "эйр громче двадцать один",
        )

        // Homophones in the same grammar split word confidence without changing the action.
        // Keep one acoustic spelling; the parser still accepts equivalent transcriptions.
        val recognitionPhrases = phrases.keys.filterNot {
            it.endsWith(" тэ вэ") || it.endsWith(" ютюб") || it.endsWith(" ютьюб") ||
                it.endsWith(" следующий") || it.endsWith(" предыдущий")
        } + (0..100).map { "эйр громкость ${SpokenVolume.words(it)}" } +
            (1..20).flatMap { listOf("эйр громче ${SpokenVolume.words(it)}", "эйр тише ${SpokenVolume.words(it)}") } +
            SpokenSeek.recognitionPhrases

        fun parse(text: String): LocalVoiceCommand? = VoiceCommandRequest.parse(text)?.command
    }
}

/** Numeric arguments travel with the command; two different volume levels are different requests. */
internal data class VoiceCommandRequest(
    val command: LocalVoiceCommand,
    val volumePercent: Int? = null,
    val seekSeconds: Int? = null,
    val volumeSteps: Int = 1,
) {
    init {
        require((command == LocalVoiceCommand.VOLUME_SET) == (volumePercent != null))
        require(volumePercent == null || volumePercent in 0..100)
        require((command == LocalVoiceCommand.SEEK) == (seekSeconds != null))
        require(seekSeconds == null || (seekSeconds != 0 && seekSeconds in -6000..6000))
        require(volumeSteps in 1..20)
        require(volumeSteps == 1 || command == LocalVoiceCommand.VOLUME_UP || command == LocalVoiceCommand.VOLUME_DOWN)
    }

    val transcript: String get() = when {
        volumePercent != null -> "эйр громкость ${SpokenVolume.words(volumePercent)}"
        seekSeconds != null -> SpokenSeek.transcript(seekSeconds)
        volumeSteps != 1 -> "эйр ${if (command == LocalVoiceCommand.VOLUME_UP) "громче" else "тише"} ${SpokenVolume.words(volumeSteps)}"
        else -> LocalVoiceCommand.phrases.entries.first { it.value == command }.key
    }

    companion object {
        fun parse(text: String): VoiceCommandRequest? {
            val normalized = text.trim().lowercase().replace('ё', 'е').replace(Regex("[\\s,!.?]+"), " ").trim()
            LocalVoiceCommand.phrases[normalized]?.let { return VoiceCommandRequest(it) }
            for ((prefix, command) in mapOf("эйр громче " to LocalVoiceCommand.VOLUME_UP, "эйр тише " to LocalVoiceCommand.VOLUME_DOWN)) {
                if (normalized.startsWith(prefix)) {
                    val steps = SpokenVolume.parse(normalized.removePrefix(prefix))?.takeIf { it in 1..20 } ?: return null
                    return VoiceCommandRequest(command, volumeSteps = steps)
                }
            }
            SpokenSeek.parse(normalized)?.let { return VoiceCommandRequest(LocalVoiceCommand.SEEK, seekSeconds = it) }
            if (!normalized.startsWith("эйр громкость ")) return null
            val percent = SpokenVolume.parse(normalized.removePrefix("эйр громкость ")) ?: return null
            return VoiceCommandRequest(LocalVoiceCommand.VOLUME_SET, percent)
        }
    }
}
