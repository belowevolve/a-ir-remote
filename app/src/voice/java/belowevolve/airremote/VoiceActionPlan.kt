package belowevolve.airremote

/** Launches always wake first; only the bare TV command toggles power. */
internal data class VoiceActionPlan(
    val awake: Boolean?,
    val appLink: String? = null,
    val keyCode: Int? = null,
    val helperLink: String? = null,
) {
    companion object {
        fun forCommand(command: LocalVoiceCommand, screenOn: Boolean, airHome: Boolean = false): VoiceActionPlan {
            check(airHome || command !in setOf(LocalVoiceCommand.FILM, LocalVoiceCommand.PC, LocalVoiceCommand.VOLUME_SET, LocalVoiceCommand.SEEK)) {
                "Для этой команды нужен Air Home на ТВ. Включите дополнения Air Home в настройках голосового управления"
            }
            val plan = when (command) {
                LocalVoiceCommand.TV_TOGGLE -> VoiceActionPlan(!screenOn)
                LocalVoiceCommand.TV_ON -> VoiceActionPlan(true)
                LocalVoiceCommand.TV_OFF -> VoiceActionPlan(false)
                LocalVoiceCommand.FILM -> VoiceActionPlan(true, "airhome://remote/film")
                LocalVoiceCommand.YOUTUBE -> VoiceActionPlan(true, if (airHome) "airhome://remote/youtube" else "https://www.youtube.com/tv")
                LocalVoiceCommand.PC -> VoiceActionPlan(true, "airhome://remote/pc")
                LocalVoiceCommand.OK -> VoiceActionPlan(null, keyCode = 23)
                LocalVoiceCommand.BACK -> VoiceActionPlan(null, keyCode = 4)
                LocalVoiceCommand.PAUSE -> VoiceActionPlan(null, keyCode = 127)
                LocalVoiceCommand.PLAY -> VoiceActionPlan(null, keyCode = 126)
                LocalVoiceCommand.NEXT -> VoiceActionPlan(null, keyCode = 87)
                LocalVoiceCommand.PREVIOUS -> VoiceActionPlan(null, keyCode = 88)
                LocalVoiceCommand.VOLUME_UP -> VoiceActionPlan(null, keyCode = 24)
                LocalVoiceCommand.VOLUME_DOWN -> VoiceActionPlan(null, keyCode = 25)
                LocalVoiceCommand.VOLUME_SET -> VoiceActionPlan(null)
                LocalVoiceCommand.SEEK -> VoiceActionPlan(null)
                LocalVoiceCommand.STOP_LISTENING -> VoiceActionPlan(null)
            }
            // BACK itself dismisses a dream. A separate helper BACK would navigate twice.
            return if (airHome && plan.awake != false && plan.appLink == null && command !in setOf(LocalVoiceCommand.STOP_LISTENING, LocalVoiceCommand.SEEK, LocalVoiceCommand.BACK))
                plan.copy(helperLink = "airhome://remote/wake") else plan
        }
    }
}
