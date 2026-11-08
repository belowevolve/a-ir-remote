package belowevolve.airremote

import com.google.protobuf.CodedOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Air Remote audio: ASCII handshake, then big-endian length + protobuf containing LE PCM. */
internal object MicTransport {
    const val RATE = 48_000
    const val QUEUED_FRAMES = 3
    const val MAX_FRAME_AGE_NS = 60_000_000L
    fun recorderBufferBytes(minimum: Int, pcmFloat: Boolean) =
        maxOf(minimum, SAMPLES * (if (pcmFloat) 4 else 2) * 2)
    fun isFresh(capturedAt: Long, now: Long) = now - capturedAt <= MAX_FRAME_AGE_NS
    const val SAMPLES = 480 // 10 ms; independent of the device's recording buffer size.

    fun handshake(input: InputStream, output: OutputStream) {
        output.write("AirRemoteA1".toByteArray(Charsets.US_ASCII))
        output.flush()
        val reply = ByteArray(11)
        DataInputStream(input).readFully(reply)
        require(reply.contentEquals("AirRemoteA2".toByteArray(Charsets.US_ASCII))) {
            "ПК не подтвердил протокол Air Remote"
        }
    }

    /** One writer owns this reusable frame; write it before calling encode again. */
    class Encoder(private val pcmFloat: Boolean, private val count: Int = SAMPLES) {
        private val pcmBytes = count * if (pcmFloat) 4 else 2
        private val prefixBytes = 1 + CodedOutputStream.computeUInt32SizeNoTag(pcmBytes)
        private val payloadBytes = prefixBytes + pcmBytes +
            CodedOutputStream.computeUInt32Size(2, RATE) +
            CodedOutputStream.computeUInt32Size(3, 1) +
            CodedOutputStream.computeUInt32Size(4, if (pcmFloat) 4 else 2)
        private val bytes = ByteArray(4 + payloadBytes)
        private val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        init {
            require(count in 1..SAMPLES)
            ByteBuffer.wrap(bytes).putInt(payloadBytes)
            CodedOutputStream.newInstance(bytes, 4, prefixBytes).apply {
                writeTag(1, 2)
                writeUInt32NoTag(pcmBytes)
                checkNoSpaceLeft()
            }
            CodedOutputStream.newInstance(bytes, 4 + prefixBytes + pcmBytes,
                payloadBytes - prefixBytes - pcmBytes).apply {
                writeUInt32(2, RATE)
                writeUInt32(3, 1)
                writeUInt32(4, if (pcmFloat) 4 else 2)
                checkNoSpaceLeft()
            }
        }

        fun encode(samples: FloatArray, muted: Boolean): ByteArray {
            require(samples.size >= count)
            pcm.position(4 + prefixBytes)
            for (i in 0 until count) {
                val value = if (muted || !samples[i].isFinite()) 0f else samples[i].coerceIn(-1f, 1f)
                if (pcmFloat) pcm.putFloat(value) else pcm.putShort((value * 32767).toInt().toShort())
            }
            return bytes
        }
    }

    fun frame(samples: FloatArray, count: Int, muted: Boolean, pcmFloat: Boolean): ByteArray =
        Encoder(pcmFloat, count).encode(samples, muted)
}
