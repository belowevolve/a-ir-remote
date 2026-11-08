package belowevolve.airremote

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal data class PcTextSnapshot(
    val text: String = "",
    val start: Int = 0,
    val end: Int = start,
) {
    fun validate() {
        require(text.length <= MAX_BYTES && '\u0000' !in text && '\r' !in text && '\t' !in text) {
            "invalid_text"
        }
        require(start in 0..text.length && end in 0..text.length) { "invalid_selection" }
        fun boundary(index: Int) =
            index == 0 ||
                index == text.length ||
                !(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())
        require(boundary(start) && boundary(end)) { "invalid_selection" }
        val encoded =
            try {
                Charsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text))
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("invalid_text")
            }
        require(encoded.remaining() <= MAX_BYTES) { "text_too_large" }
    }

    companion object {
        const val MAX_BYTES = 16 * 1024
        const val CAPABILITY = "native_text_v4"
    }
}

internal enum class PcTextStatus {
    CLOSED,
    CONNECTING,
    ACTIVE,
    STOPPED,
}

internal data class PcTextAck(val revision: Long, val context: PcTextSnapshot?)

internal data class PcTextFrame(
    val revision: Long,
    val snapshot: PcTextSnapshot,
    val key: String? = null,
    val count: Int = 1,
)

/** One snapshot in flight, latest edits coalesced, explicit keys ordered between drafts. */
internal class PcTextPlan(initial: PcTextSnapshot = PcTextSnapshot()) {
    init {
        initial.validate()
    }

    private var acknowledged = initial
    private var desired = acknowledged
    private var revision = 0L
    private var flight: PcTextFrame? = null
    private var stopped = false
    private val queued = java.util.ArrayDeque<PcTextFrame>()

    fun key(name: String, count: Int = 1): Boolean {
        if (stopped || queued.size >= 32) return false
        require(name == "enter" || name == "backspace" || name == "clear")
        require(count in 1..64)
        queued.add(PcTextFrame(0, desired))
        desired = PcTextField.afterKey(desired, name, count)
        queued.add(PcTextFrame(0, desired, name, count))
        return true
    }

    fun edit(snapshot: PcTextSnapshot): Boolean {
        if (stopped) return false
        snapshot.validate()
        desired = snapshot
        return true
    }

    fun next(): PcTextFrame? {
        if (stopped || flight != null) return null
        val pending =
            if (queued.isNotEmpty()) queued.removeFirst()
            else {
                if (desired == acknowledged) return null
                PcTextFrame(0, desired)
            }
        val frame = pending.copy(revision = ++revision)
        flight = frame
        return frame
    }

    fun acknowledge(receivedRevision: Long, context: PcTextSnapshot? = null): PcTextSnapshot? {
        val frame = flight
        if (stopped || frame == null || frame.revision != receivedRevision) {
            stop()
            throw IllegalStateException("invalid_ack")
        }
        acknowledged = if (frame.key != null) context ?: PcTextSnapshot() else frame.snapshot
        flight = null
        if (frame.key == null || context == null) return null
        context.validate()
        var oldBase = frame.snapshot
        var newBase: PcTextSnapshot = context
        var beforeKey: PcTextSnapshot = context
        val rebased = java.util.ArrayDeque<PcTextFrame>()
        for (pending in queued) {
            val snapshot =
                if (pending.key == null) {
                    PcTextField.rebase(oldBase, pending.snapshot, newBase).also { beforeKey = it }
                } else {
                    PcTextField.afterKey(beforeKey, pending.key, pending.count).also {
                        oldBase = pending.snapshot
                        newBase = it
                        beforeKey = it
                    }
                }
            rebased.add(pending.copy(snapshot = snapshot))
        }
        queued.clear()
        queued.addAll(rebased)
        desired = PcTextField.rebase(oldBase, desired, newBase)
        return desired
    }

    fun value(): PcTextSnapshot = desired

    fun stop() {
        stopped = true
        flight = null
        queued.clear()
    }
}
