package belowevolve.airremote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class PcTextPlanTest {
    @Test fun editorContextAfterEnterPreservesPreviousLinesAndFreshTyping() {
        val plan = PcTextPlan()
        plan.edit(PcTextSnapshot("first", 5)); plan.key("enter")
        val text = plan.next()!!; plan.acknowledge(text.revision)
        val enter = plan.next()!!
        assertEquals(PcTextSnapshot("first\n", 6), plan.value())
        plan.edit(PcTextSnapshot("first\nnext", 10))
        val combined = plan.acknowledge(enter.revision, PcTextSnapshot("first\n", 6))
        assertEquals(PcTextSnapshot("first\nnext", 10), combined)
        assertEquals(combined, plan.next()!!.snapshot)
    }

    @Test fun lateEnterContextRebasesAllQueuedParagraphsWithoutFlashing() {
        val plan = PcTextPlan()
        plan.edit(PcTextSnapshot("one", 3)); plan.key("enter")
        plan.acknowledge(plan.next()!!.revision)
        val firstEnter = plan.next()!!
        plan.edit(PcTextSnapshot("one\ntwo", 7)); plan.key("enter")
        plan.edit(PcTextSnapshot("one\ntwo\nthree", 13))
        assertEquals(plan.value(), plan.acknowledge(firstEnter.revision, PcTextSnapshot("one\n", 4)))
        val second = plan.next()!!
        assertEquals("one\ntwo", second.snapshot.text)
        plan.acknowledge(second.revision)
        val secondEnter = plan.next()!!
        assertEquals("enter", secondEnter.key)
        assertEquals(PcTextSnapshot("one\ntwo\nthree", 13),
            plan.acknowledge(secondEnter.revision, PcTextSnapshot("one\ntwo\n", 8)))
    }

    @Test fun nativeEnterPreservesPendingCorrectionAndFreshTypingWithoutReplay() {
        val plan = PcTextPlan()
        plan.edit(PcTextSnapshot("teh", 3))
        val first = plan.next()!!
        plan.edit(PcTextSnapshot("the", 3))
        assertTrue(plan.key("enter"))
        plan.edit(PcTextSnapshot("the\nnext", 8))
        assertNull(plan.next())
        plan.acknowledge(first.revision)
        val correction = plan.next()!!
        assertEquals("the", correction.snapshot.text)
        plan.acknowledge(correction.revision)
        val enter = plan.next()!!
        assertEquals("enter", enter.key)
        plan.acknowledge(enter.revision, PcTextSnapshot())
        val fresh = plan.next()!!
        assertEquals("next", fresh.snapshot.text)
        assertNull(fresh.key)
        plan.acknowledge(fresh.revision)
        assertNull(plan.next())
    }

    @Test fun multipleEnterBarriersAndEmptyBackspaceKeepTheirOrder() {
        val plan = PcTextPlan()
        plan.edit(PcTextSnapshot("one", 3))
        assertTrue(plan.key("enter"))
        plan.edit(PcTextSnapshot("one\ntwo", 7))
        assertTrue(plan.key("enter"))
        assertTrue(plan.key("backspace", 2))
        val seen = mutableListOf<String>()
        while (true) {
            val frame = plan.next() ?: break
            seen += frame.key ?: frame.snapshot.text
            if (frame.key == "backspace") assertEquals(2, frame.count)
            plan.acknowledge(frame.revision, if (frame.key == null) null else PcTextSnapshot())
        }
        assertEquals(listOf("one", "enter", "two", "enter", "", "backspace"), seen)
    }

    @Test fun clearBarrierResetsLaterTypingEvenWithAnEarlierEnterAwaitingAck() {
        val plan = PcTextPlan()
        plan.edit(PcTextSnapshot("one", 3)); plan.key("enter")
        plan.acknowledge(plan.next()!!.revision)
        val enter = plan.next()!!
        plan.key("clear"); plan.edit(PcTextSnapshot("new", 3))
        assertEquals(PcTextSnapshot("new", 3), plan.acknowledge(enter.revision, PcTextSnapshot("one\n", 4)))
        plan.acknowledge(plan.next()!!.revision)
        val clear = plan.next()!!
        assertEquals("clear", clear.key)
        assertEquals(PcTextSnapshot("new", 3), plan.acknowledge(clear.revision, PcTextSnapshot()))
        assertEquals("new", plan.next()!!.snapshot.text)
    }

    @Test fun submitChatRebasesFastTypingAndEnterInMiddleRetainsSuffix() {
        val base = PcTextSnapshot("before\nafter", 7)
        assertEquals(PcTextSnapshot("next", 4), PcTextField.rebase(base, PcTextSnapshot("before\nnextafter", 11), PcTextSnapshot()))
        assertEquals(PcTextSnapshot("before\nnextafter", 11), PcTextField.rebase(base, PcTextSnapshot("before\nnextafter", 11), base))
        assertEquals(PcTextSnapshot("a\nb", 2), PcTextField.afterKey(PcTextSnapshot("ab", 1), "enter", 1))
    }

    @Test fun onlyLatestCompleteImeSnapshotFollowsAcknowledgement() {
        val plan = PcTextPlan()
        assertNull(plan.next())
        plan.edit(PcTextSnapshot("при", 3))
        val first = plan.next()!!
        plan.edit(PcTextSnapshot("привет", 6))
        plan.edit(PcTextSnapshot("здравствуйте ", 13))
        assertNull(plan.next())
        plan.acknowledge(first.revision)
        val second = plan.next()!!
        assertEquals(2L, second.revision)
        assertEquals("здравствуйте ", second.snapshot.text)
        plan.acknowledge(second.revision)
        assertNull(plan.next())
    }

    @Test fun initialFieldIsSeededWithoutWritingAndLfNeverResetsIt() {
        val initial = PcTextSnapshot("before OLD after", 7, 10)
        val plan = PcTextPlan(initial)
        assertNull(plan.next())
        val value = PcTextField.initialize(initial, TextFieldValue("new\nline", TextRange(8)))
        plan.edit(PcTextField.snapshot(value))
        assertEquals("before new\nline after", plan.next()!!.snapshot.text)
        plan.acknowledge(1)
        assertNull(plan.next())
        plan.edit(PcTextSnapshot("before new\nline! after", 16))
        val next = plan.next()!!
        assertEquals(2L, next.revision)
        assertEquals("before new\nline! after", next.snapshot.text)
    }

    @Test fun selectionAndReplacementUseUtf16Offsets() {
        val plan = PcTextPlan()
        val original = PcTextSnapshot("A😀Б", 1, 3)
        plan.edit(original)
        assertEquals(original, plan.next()!!.snapshot)
        plan.acknowledge(1)
        plan.edit(PcTextSnapshot("A中Б", 2))
        assertEquals(PcTextSnapshot("A中Б", 2), plan.next()!!.snapshot)
    }

    @Test fun stoppingOrWrongAckNeverReplaysPendingText() {
        val plan = PcTextPlan()
        plan.edit(PcTextSnapshot("one", 3))
        plan.next()
        plan.edit(PcTextSnapshot("two", 3))
        assertThrows(IllegalStateException::class.java) { plan.acknowledge(2) }
        assertNull(plan.next())
        assertFalse(plan.edit(PcTextSnapshot("three", 5)))
        val cancelled = PcTextPlan()
        cancelled.edit(PcTextSnapshot("unsent", 6))
        cancelled.stop()
        assertNull(cancelled.next())
    }

    @Test fun initialSelectionAndFreshCompositionArePreserved() {
        val initial = PcTextSnapshot("prefix OLD suffix", 7, 10)
        assertEquals(TextFieldValue(initial.text, TextRange(7, 10)), PcTextField.initialize(initial, TextFieldValue()))
        val draft = TextFieldValue("прив", TextRange(4), TextRange(0, 4))
        val merged = PcTextField.initialize(initial, draft)
        assertEquals("prefix прив suffix", merged.text)
        assertEquals(TextRange(11), merged.selection)
        assertEquals(TextRange(7, 11), merged.composition)
        assertEquals("прив", draft.text)
    }

    @Test fun crlfNormalizationMapsSelectionAndCompositionWithoutSplittingEmoji() {
        val value = TextFieldValue("a\r\n😀\rb", TextRange(5, 7), TextRange(3, 5))
        val normalized = PcTextField.normalize(value)
        assertEquals("a\n😀\nb", normalized.text)
        assertEquals(TextRange(4, 6), normalized.selection)
        assertEquals(TextRange(2, 4), normalized.composition)
        PcTextField.snapshot(normalized)
    }

    @Test fun boundsApplyToFullMergedFieldAndRejectMalformedText() {
        PcTextSnapshot("a".repeat(16 * 1024)).validate()
        PcTextSnapshot("😀".repeat(4096)).validate()
        assertThrows(IllegalArgumentException::class.java) { PcTextSnapshot("😀".repeat(4097)).validate() }
        assertThrows(IllegalArgumentException::class.java) { PcTextSnapshot("A😀B", 2).validate() }
        assertThrows(IllegalArgumentException::class.java) { PcTextSnapshot("\uD800").validate() }
        assertThrows(IllegalArgumentException::class.java) { PcTextSnapshot("a\u0000b").validate() }
        assertThrows(IllegalArgumentException::class.java) { PcTextSnapshot("a\tb").validate() }
        assertThrows(IllegalArgumentException::class.java) { PcTextSnapshot("a\rb").validate() }
        assertThrows(IllegalArgumentException::class.java) { PcTextSnapshot("abc", 4).validate() }
        val full = PcTextSnapshot("a".repeat(16 * 1024), 0)
        assertThrows(IllegalArgumentException::class.java) { PcTextField.initialize(full, TextFieldValue("x", TextRange(1))) }
    }
}
