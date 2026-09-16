package belowevolve.airremote

import com.google.protobuf.ByteString
import belowevolve.airremote.mic.MicrophoneProtocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** AndroidMic TCP: ASCII handshake, then big-endian length + protobuf containing LE PCM. */
internal object MicTransport {
    const val RATE = 48_000
    const val SAMPLES = 480 // 10 ms; independent of the device's recording buffer size.

    fun handshake(input: InputStream, output: OutputStream) {
        output.write("AndroidMic1".toByteArray(Charsets.US_ASCII))
        output.flush()
        val reply = ByteArray(11)
        DataInputStream(input).readFully(reply)
        require(reply.contentEquals("AndroidMic2".toByteArray(Charsets.US_ASCII))) {
            "ПК не подтвердил протокол AndroidMic"
        }
    }

    fun frame(samples: FloatArray, count: Int, muted: Boolean, pcmFloat: Boolean): ByteArray {
        require(count in (1..samples.size))
        val pcm = ByteBuffer.allocate(count * if (pcmFloat) 4 else 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val value = if (muted) 0f else samples[i].coerceIn(-1f, 1f)
            if (pcmFloat) pcm.putFloat(value) else pcm.putShort((value * 32767).toInt().toShort())
        }
        val message = MicrophoneProtocol.AudioPacket.newBuilder()
            .setBuffer(ByteString.copyFrom(pcm.array())).setSampleRate(RATE)
            .setChannelCount(1).setAudioFormat(if (pcmFloat) 4 else 2).build().toByteArray()
        return ByteBuffer.allocate(4 + message.size).putInt(message.size).put(message).array()
    }

    fun write(output: OutputStream, frame: ByteArray) { DataOutputStream(output).write(frame) }
}
