package belowevolve.airremote

import java.net.InetAddress

/** Validate numerically before InetAddress so malformed input never triggers DNS. */
internal fun lanAddress(value: String): InetAddress {
    require(value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) &&
        value.split('.').all { it.toInt() in 0..255 }) {
        "Укажи IPv4-адрес ПК, например 192.168.1.20"
    }
    val address = InetAddress.getByName(value)
    require(address.isSiteLocalAddress || address.isLinkLocalAddress) { "Нужен локальный IP-адрес ПК" }
    return address
}
