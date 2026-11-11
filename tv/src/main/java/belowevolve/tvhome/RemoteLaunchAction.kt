package belowevolve.tvhome

/** Fixed, browsable routes used by the paired Android TV remote. */
internal enum class RemoteLaunchAction {
    FILM, YOUTUBE, PC, VOLUME, SEEK, WAKE;

    val shortcut: String?
        get() = when (this) {
            FILM -> "mov.film"
            YOUTUBE -> "io.gh.reisxd.tizentube.cobalt"
            PC -> "hdmi-open"
            VOLUME, SEEK, WAKE -> null
        }

    companion object {
        const val BROADCAST = "belowevolve.tvhome.REMOTE_LAUNCH"
        fun fromRoute(scheme: String?, host: String?, path: String?): RemoteLaunchAction? {
            if (scheme != "airhome" || host != "remote") return null
            return when (path) {
                "/film" -> FILM
                "/youtube" -> YOUTUBE
                "/pc" -> PC
                "/volume" -> VOLUME
                "/seek" -> SEEK
                "/wake" -> WAKE
                else -> null
            }
        }

        fun volumePercent(value: String?): Int? = value?.toIntOrNull()?.takeIf { it in 0..100 }
        fun seekSeconds(value: String?): Int? = value?.toIntOrNull()?.takeIf { it != 0 && it in -6000..6000 }
    }
}
