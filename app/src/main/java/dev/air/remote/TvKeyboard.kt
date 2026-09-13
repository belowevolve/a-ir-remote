package dev.air.remote

import remote.Remotemessage.*

/** Android IME offsets are UTF-16 code units. */
data class KeyboardText(val text: String = "", val start: Int = text.length, val end: Int = start) {
    fun normalized() = copy(start = start.coerceIn(0, text.length), end = end.coerceIn(0, text.length))
}

data class KeyboardUpdate(
    val value: KeyboardText,
    val epoch: Long,
    val revision: Long = 0,
    val show: Boolean = false,
    val inputType: Int = 1,
    val imeOptions: Int = 0,
)

/** Accessed under TvClient's write lock so notifications and writes share one state. */
class TvKeyboard {
    private var contextCounter = 0
    private var textRevision = -1
    private var inputType = 1
    private var imeOptions = 0
    private var epoch = 1L
    private var revision = 0L
    private var value = KeyboardText()
    private var hasField = false
    private val echoes = ArrayDeque<KeyboardText>()
    fun snapshot(show: Boolean = false) = KeyboardUpdate(value, epoch, revision, show, inputType, imeOptions)

    fun reset() {
        contextCounter = 0
        inputType = 1
        imeOptions = 0
        newEditor()
    }

    private fun newEditor() {
        epoch++
        revision = 0
        textRevision = -1
        value = KeyboardText()
        hasField = false
        echoes.clear()
    }

    fun receive(message: RemoteMessage): KeyboardUpdate? {
        var changed = false
        var show = false
        if (message.hasRemoteImeKeyInject()) {
            val context = message.remoteImeKeyInject
            if (context.hasAppInfo()) {
                val app = context.appInfo
                if (app.counter != contextCounter || (hasField && !context.hasTextFieldStatus())) newEditor()
                contextCounter = app.counter
                inputType = app.int2
                imeOptions = app.int3
                changed = true
            }
            if (context.hasTextFieldStatus()) {
                show = !hasField
                accept(context.textFieldStatus)
                changed = true
            }
        }
        // Counter-only batch notifications carry no field snapshot. Do not invalidate
        // queued typing or reuse their counters for outgoing compatibility edits.
        if (message.hasRemoteImeShowRequest()) {
            val request = message.remoteImeShowRequest
            if (request.hasRemoteTextFieldStatus()) accept(request.remoteTextFieldStatus)
            show = true
            changed = true
        }
        return if (changed) snapshot(show) else null
    }

    private fun accept(status: RemoteTextFieldStatus) {
        if (status.counterField < textRevision) return
        textRevision = status.counterField
        hasField = true
        val incoming = KeyboardText(status.value, status.start, status.end).normalized()
        if (incoming == value) {
            echoes.clear()
            return
        }
        val echo = echoes.indexOfFirst { it == incoming }
        if (echo >= 0) {
            repeat(echo + 1) { echoes.removeFirst() }
            return
        }
        // A TV-side edit supersedes snapshots still waiting in the phone's queue.
        epoch++
        revision = 0
        echoes.clear()
        value = incoming
    }

    fun edit(expectedEpoch: Long, next: KeyboardText, localRevision: Long = revision + 1): RemoteMessage? {
        if (expectedEpoch != epoch) return null
        val normalized = next.normalized()
        revision = localRevision
        if (normalized == value) return null
        rememberEcho(value)
        val batch = editBatch()
        if (normalized.text != value.text) {
            val change = difference(value.text, normalized.text)
            batch.addEditInfo(RemoteEditInfo.newBuilder().setTextFieldStatus(
                RemoteImeObject.newBuilder().setStart(change.start).setEnd(change.end).setValue(change.text),
            ))
            rememberEcho(KeyboardText(normalized.text, change.start + change.text.length))
        }
        batch.addEditInfo(RemoteEditInfo.newBuilder().setSelection(
            RemoteImeSelection.newBuilder().setStart(normalized.start).setEnd(normalized.end),
        ))
        rememberEcho(normalized)
        value = normalized
        return RemoteMessage.newBuilder().setRemoteImeBatchEdit(batch).build()
    }

    // Use the service's zero-counter compatibility path for both edits and actions.
    // On the tested TV, echoing its batch counters (1/1) silently drops active-field
    // edits; zero counters apply immediately and produce authoritative text updates.
    private fun editBatch() = RemoteImeBatchEdit.newBuilder().setImeCounter(0).setFieldCounter(0)

    private fun rememberEcho(text: KeyboardText) {
        if (echoes.lastOrNull() != text) echoes.addLast(text)
        while (echoes.size > 128) echoes.removeFirst()
    }

    fun backspace(expectedEpoch: Long): RemoteMessage? = if (expectedEpoch == epoch) key(67) else null

    fun submit(expectedEpoch: Long): RemoteMessage? {
        if (expectedEpoch != epoch) return null
        val action = imeOptions and 0xff
        return if (hasField && action in 2..7) {
            RemoteMessage.newBuilder().setRemoteImeBatchEdit(
                editBatch()
                    .addEditInfo(RemoteEditInfo.newBuilder().setEditorAction(RemoteImeAction.newBuilder().setAction(action))),
            ).build()
        } else key(66)
    }

    companion object {
        private fun key(code: Int) = RemoteMessage.newBuilder().setRemoteKeyInject(
            RemoteKeyInject.newBuilder().setKeyCodeValue(code).setDirection(RemoteDirection.SHORT),
        ).build()

        fun difference(before: String, after: String): KeyboardText {
            var start = 0
            while (start < before.length && start < after.length && before[start] == after[start]) start++
            if (start > 0 && start < before.length && Character.isLowSurrogate(before[start])) start--
            var oldEnd = before.length
            var newEnd = after.length
            while (oldEnd > start && newEnd > start && before[oldEnd - 1] == after[newEnd - 1]) {
                oldEnd--
                newEnd--
            }
            if (oldEnd < before.length && oldEnd > start && Character.isLowSurrogate(before[oldEnd])) {
                oldEnd++
                newEnd++
            }
            return KeyboardText(after.substring(start, newEnd), start, oldEnd)
        }
    }
}
