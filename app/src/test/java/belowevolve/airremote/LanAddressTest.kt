package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class LanAddressTest {
    @Test fun acceptsPrivateAndLinkLocalAddresses() {
        for (address in listOf("192.168.1.20", "10.0.0.1", "172.16.0.2", "169.254.1.2")) {
            assertEquals(address, lanAddress(address).hostAddress)
        }
    }
    @Test fun rejectsMalformedPublicAndLoopbackAddresses() {
        for (address in listOf("", "localhost", "192.168.1", "192.168.1.256", "1.1.1.1", "127.0.0.1", "0.0.0.0", "::1")) {
            assertThrows(IllegalArgumentException::class.java) { lanAddress(address) }
        }
    }
}
