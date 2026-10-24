package belowevolve.airremote

/** Guarded by TvClient's write lock; TV requests and microphone writes share one session. */
internal class TvVoiceSession {
    var requested: Int? = null
        private set
    var active: Int? = null
        private set

    fun request(id: Int) {
        if (requested == id || active == id) return
        requested = id
        active = null
    }
    fun begin(): Int {
        check(active == null) { "Микрофон уже включён" }
        val id = requested ?: error("Открой голосовой поиск в приложении на ТВ, затем нажми «Голос»")
        requested = null
        active = id
        return id
    }
    fun end(id: Int): Boolean {
        val matched = requested == id || active == id
        if (requested == id) requested = null
        if (active == id) active = null
        return matched
    }
    fun reset() { requested = null; active = null }
}
