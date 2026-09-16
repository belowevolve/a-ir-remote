package belowevolve.airremote

import belowevolve.airremote.mic.MicrophoneProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MicTransportTest {
    @Test fun matchesIndependentWireFixture() {
        // 14-byte protobuf: buffer field, 48000 rate, mono, Android PCM_FLOAT = 4.
        val expected = byteArrayOf(0, 0, 0, 14, 10, 4, 0, 0, 0, 63, 16, -128, -9, 2, 24, 1, 32, 4)
        assertArrayEquals(expected, MicTransport.frame(floatArrayOf(0.5f), 1, false, true))
    }

    @Test fun readsFragmentedHandshakeFully() {
        val input = object : ByteArrayInputStream("AndroidMic2".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        val output = ByteArrayOutputStream()
        MicTransport.handshake(input, output)
        assertEquals("AndroidMic1", output.toString("US-ASCII"))
    }

    @Test(expected = EOFException::class) fun rejectsTruncatedHandshake() {
        MicTransport.handshake(ByteArrayInputStream("Android".toByteArray()), ByteArrayOutputStream())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOtherServer() {
        MicTransport.handshake(ByteArrayInputStream("XXXXXXXXXXX".toByteArray()), ByteArrayOutputStream())
    }

    @Test fun muteAndPartialReadsHaveNoStaleTail() {
        for (floatPcm in listOf(true, false)) {
            val frame = MicTransport.frame(floatArrayOf(0.75f, -0.5f, 1f), 2, true, floatPcm)
            val payload = MicrophoneProtocol.AudioPacket.parseFrom(frame.copyOfRange(4, frame.size))
            assertEquals(2 * if (floatPcm) 4 else 2, payload.buffer.size())
            assertTrue(payload.buffer.toByteArray().all { it == 0.toByte() })
        }
    }

    @Test fun tcpReceiverDecodesConsecutiveFramesAndPcm16() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            server.soTimeout = 3000
            val received = executor.submit<List<MicrophoneProtocol.AudioPacket>> {
                server.accept().use { peer ->
                    peer.soTimeout = 3000
                    val input = DataInputStream(peer.getInputStream())
                    val handshake = ByteArray(11)
                    input.readFully(handshake)
                    assertEquals("AndroidMic1", String(handshake))
                    peer.getOutputStream().write("AndroidMic2".toByteArray())
                    List(2) {
                        val bytes = ByteArray(input.readInt())
                        input.readFully(bytes)
                        MicrophoneProtocol.AudioPacket.parseFrom(bytes)
                    }
                }
            }
            try {
                Socket("127.0.0.1", server.localPort).use { client ->
                    client.soTimeout = 3000
                    MicTransport.handshake(client.getInputStream(), client.getOutputStream())
                    MicTransport.write(client.getOutputStream(), MicTransport.frame(floatArrayOf(-1f, 1f), 2, false, false))
                    MicTransport.write(client.getOutputStream(), MicTransport.frame(FloatArray(480) { 0.25f }, 480, false, true))
                }
                val packets = received.get(4, TimeUnit.SECONDS)
                assertEquals(48_000, packets[0].sampleRate)
                assertEquals(1, packets[0].channelCount)
                assertEquals(2, packets[0].audioFormat)
                val pcm = ByteBuffer.wrap(packets[0].buffer.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(-32767, pcm.short.toInt())
                assertEquals(32767, pcm.short.toInt())
                assertEquals(1920, packets[1].buffer.size())
                assertEquals(4, packets[1].audioFormat)
            } finally { executor.shutdownNow() }
        }
    }
}
