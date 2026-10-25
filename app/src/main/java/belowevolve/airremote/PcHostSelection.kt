package belowevolve.airremote

/** Keeps late Bluetooth callbacks from overriding an explicit user selection. */
internal class PcHostSelection(var address: String?, var allowed: Boolean) {
    private val blocked = mutableSetOf<String>()

    fun select(address: String) {
        this.address?.takeIf { it != address }?.let(blocked::add)
        blocked.remove(address)
        this.address = address
        allowed = true
    }

    fun disconnect() {
        address?.let(blocked::add)
        allowed = false
    }

    fun forget() {
        disconnect()
        address = null
    }

    fun pair() {
        address?.let(blocked::add)
        address = null
        allowed = true
    }

    fun accepts(address: String) = allowed && address !in blocked && (this.address == null || this.address == address)
}
