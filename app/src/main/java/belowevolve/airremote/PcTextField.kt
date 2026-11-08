package belowevolve.airremote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Logical LF offsets; initial response and drafts never leave this explicit session. */
internal object PcTextField {
    fun normalize(value: TextFieldValue): TextFieldValue {
        if ('\r' !in value.text) return value
        fun offset(index: Int): Int {
            val end = index.coerceIn(0, value.text.length)
            var removed = 0
            for (i in 1 until end) if (value.text[i] == '\n' && value.text[i - 1] == '\r') removed++
            return end - removed
        }
        return TextFieldValue(
            value.text.replace("\r\n", "\n").replace('\r', '\n'),
            TextRange(offset(value.selection.start), offset(value.selection.end)),
            value.composition?.let { TextRange(offset(it.start), offset(it.end)) },
        )
    }

    fun snapshot(value: TextFieldValue): PcTextSnapshot =
        PcTextSnapshot(value.text, value.selection.start, value.selection.end).also {
            it.validate()
        }

    fun initialize(initial: PcTextSnapshot, freshDraft: TextFieldValue): TextFieldValue {
        initial.validate()
        val draft = normalize(freshDraft)
        snapshot(draft)
        if (draft.text.isEmpty())
            return TextFieldValue(initial.text, TextRange(initial.start, initial.end))
        val start = minOf(initial.start, initial.end)
        val end = maxOf(initial.start, initial.end)
        return TextFieldValue(
                initial.text.replaceRange(start, end, draft.text),
                TextRange(start + draft.selection.start, start + draft.selection.end),
                draft.composition?.let { TextRange(start + it.start, start + it.end) },
            )
            .also { snapshot(it) }
    }

    fun rebase(
        base: PcTextSnapshot,
        edited: PcTextSnapshot,
        actual: PcTextSnapshot,
    ): PcTextSnapshot {
        if (base == actual) return edited
        if (base.text == edited.text) {
            fun at(pos: Int) = (actual.start + pos - base.start).coerceIn(0, actual.text.length)
            return actual.copy(start = at(edited.start), end = at(edited.end))
        }
        var prefix = 0
        while (
            prefix < minOf(base.text.length, edited.text.length) &&
                base.text[prefix] == edited.text[prefix]
        ) prefix++
        if (prefix > 0 && prefix < base.text.length && base.text[prefix].isLowSurrogate()) prefix--
        var suffix = 0
        while (
            suffix < minOf(base.text.length, edited.text.length) - prefix &&
                base.text[base.text.lastIndex - suffix] ==
                    edited.text[edited.text.lastIndex - suffix]
        ) suffix++
        if (
            suffix > 0 &&
                suffix < base.text.length &&
                base.text[base.text.length - suffix].isLowSurrogate()
        )
            suffix--
        val from = (actual.start + prefix - base.start).coerceIn(0, actual.text.length)
        val to =
            (actual.end + base.text.length - suffix - base.end).coerceIn(from, actual.text.length)
        val replacement = edited.text.substring(prefix, edited.text.length - suffix)
        val text = actual.text.replaceRange(from, to, replacement)
        fun at(pos: Int) = (from + pos - prefix).coerceIn(0, text.length)
        return PcTextSnapshot(text, at(edited.start), at(edited.end)).also { it.validate() }
    }

    fun afterKey(value: PcTextSnapshot, key: String, count: Int): PcTextSnapshot =
        if (key == "enter") {
            val start = minOf(value.start, value.end)
            val end = maxOf(value.start, value.end)
            val text = value.text.replaceRange(start, end, "\n".repeat(count))
            PcTextSnapshot(text, start + count).also { it.validate() }
        } else PcTextSnapshot()
}
