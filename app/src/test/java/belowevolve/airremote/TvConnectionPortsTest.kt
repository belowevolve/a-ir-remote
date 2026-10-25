package belowevolve.airremote

import java.io.IOException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLProtocolException
import org.junit.Assert.*
import org.junit.Test

class TvConnectionPortsTest {
    @Test fun certificateRejectionOnFirstReadFallsBackAndRemembersWorkingPort() {
        val ports = TvConnectionPorts()
        val attempts = mutableListOf<Int>()
        ports.connect("tv") { port, ready ->
            attempts += port
            if (port == 6465) throw SSLProtocolException("certificate_unknown")
            ready()
        }
        ports.connect("tv") { port, ready -> attempts += port; ready() }
        assertEquals(listOf(6465, 6466, 6466), attempts)
    }

    @Test fun disconnectedEstablishedSessionDoesNotSilentlyCreateAnotherSession() {
        val attempts = mutableListOf<Int>()
        assertThrows(IOException::class.java) {
            TvConnectionPorts().connect("tv") { port, ready ->
                attempts += port
                ready()
                throw IOException("disconnected")
            }
        }
        assertEquals(listOf(6465), attempts)
    }

    @Test fun cancellationDoesNotTryAnotherPort() {
        val attempts = mutableListOf<Int>()
        assertThrows(CancellationException::class.java) {
            TvConnectionPorts().connect("tv") { port, _ ->
                attempts += port
                throw CancellationException()
            }
        }
        assertEquals(listOf(6465), attempts)
    }

    @Test fun unavailableCachedPortCanFallBackWithoutAffectingOtherTelevisions() {
        val ports = TvConnectionPorts()
        ports.connect("tv") { port, ready ->
            if (port == 6465) throw IOException()
            ready()
        }
        val attempts = mutableListOf<Int>()
        ports.connect("tv") { port, ready ->
            attempts += port
            if (port == 6466) throw IOException()
            ready()
        }
        ports.connect("another-tv") { port, ready -> attempts += port; ready() }
        assertEquals(listOf(6466, 6465, 6465), attempts)
    }
}
