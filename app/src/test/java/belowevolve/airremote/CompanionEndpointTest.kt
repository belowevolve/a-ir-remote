package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class CompanionEndpointTest {
    private val valid = "airremote://192.168.1.20:54346/${"a".repeat(64)}/${"b".repeat(43)}"
    @Test fun pairingCodeRoundTrips() { assertEquals(valid, CompanionEndpoint.parse(valid).code()) }
    @Test fun rejectsUntrustedAddressesAndMalformedCredentials() {
        for (bad in listOf(valid.replace("192.168.1.20", "8.8.8.8"), valid.replace("192.168.1.20", "example.com"), valid.replace("airremote:", "http:"), valid + "?token=other", valid.replace("a".repeat(64), "bad"))) {
            assertThrows(IllegalArgumentException::class.java) { CompanionEndpoint.parse(bad) }
        }
    }
    @Test fun handshakeDoesNotConsumeFollowingAudio() {
        val input = ByteArrayInputStream("OK\nAirRemoteA2".toByteArray())
        assertEquals("OK", CompanionEndpoint.readLine(input))
        assertEquals("AirRemoteA2", input.readBytes().toString(Charsets.UTF_8))
    }
    @Test fun boundsServerMessages() { assertThrows(java.io.IOException::class.java) { CompanionEndpoint.readLine(ByteArrayInputStream(ByteArray(4097) { 65 })) } }
}
