package dev.air.remote

import org.junit.Assert.*
import org.junit.Test
import remote.Remotemessage.*

class TvKeyboardTest {
    private fun field(text: String, start: Int = text.length, end: Int = start, counter: Int = 4) =
        RemoteTextFieldStatus.newBuilder().setCounterField(counter).setValue(text).setStart(start).setEnd(end).build()

    private fun session(text: String, counter: Int = 7, action: Int = 3) =
        RemoteMessage.newBuilder().setRemoteImeKeyInject(RemoteImeKeyInject.newBuilder()
            .setAppInfo(RemoteAppInfo.newBuilder().setCounter(counter).setInt2(1).setInt3(action))
            .setTextFieldStatus(field(text))).build()

    @Test fun opensWithExistingText() {
        val keyboard = TvKeyboard()
        val update = keyboard.receive(session("Привет"))!!
        assertTrue(update.show)
        assertEquals("Привет", update.value.text)
    }

    @Test fun diffBasedEditCorrectlyUpdatesText() {
        val keyboard = TvKeyboard()
        keyboard.receive(session("abc"))
        
        // Backspace: abc -> ab
        val edit = keyboard.edit(keyboard.snapshot().epoch, KeyboardText("ab"))!!
        val status = edit.remoteImeBatchEdit.getEditInfo(0).textFieldStatus
        assertEquals(2, status.start)
        assertEquals(3, status.end)
        assertEquals("", status.value)
    }

    @Test fun contextCountersDoNotOverrideCompatibilityMode() {
        val keyboard = TvKeyboard()
        keyboard.receive(session("abc", counter = 10))
        val batch = keyboard.edit(keyboard.snapshot().epoch, KeyboardText("def"))!!.remoteImeBatchEdit
        // Context and text revision are NOT command counters.
        assertEquals(0, batch.imeCounter)
        assertEquals(0, batch.fieldCounter)
    }

    @Test fun resetClearsStateAndEpoch() {
        val keyboard = TvKeyboard()
        keyboard.receive(session("secret"))
        val oldEpoch = keyboard.snapshot().epoch
        keyboard.reset()
        assertEquals("", keyboard.snapshot().value.text)
        assertNotEquals(oldEpoch, keyboard.snapshot().epoch)
    }

    @Test fun searchUsesEditorAction() {
        val keyboard = TvKeyboard()
        keyboard.receive(session("query", action = 3))
        keyboard.receive(RemoteMessage.newBuilder().setRemoteImeBatchEdit(
            RemoteImeBatchEdit.newBuilder().setImeCounter(1).setFieldCounter(1),
        ).build())
        val batch = keyboard.submit(keyboard.snapshot().epoch)!!.remoteImeBatchEdit
        assertEquals(0, batch.imeCounter)
        assertEquals(0, batch.fieldCounter)
        val action = keyboard.submit(keyboard.snapshot().epoch)!!.remoteImeBatchEdit.getEditInfo(0)
        assertEquals(3, action.editorAction.action)
    }
    @Test fun realTvTraceUsesCompatibilityEditsDespiteNonzeroNotificationCounters() {
        val keyboard = TvKeyboard()
        keyboard.receive(RemoteMessage.newBuilder().setRemoteImeKeyInject(
            RemoteImeKeyInject.newBuilder().setAppInfo(RemoteAppInfo.newBuilder().setCounter(1))
                .setTextFieldStatus(field("", counter = 240)),
        ).build())
        keyboard.receive(RemoteMessage.newBuilder().setRemoteImeBatchEdit(
            RemoteImeBatchEdit.newBuilder().setImeCounter(1).setFieldCounter(1),
        ).build())
        keyboard.receive(RemoteMessage.newBuilder().setRemoteImeShowRequest(
            RemoteImeShowRequest.newBuilder().setRemoteTextFieldStatus(field("old", counter = 245)),
        ).build())
        val message = keyboard.edit(keyboard.snapshot().epoch, KeyboardText("oldX"))!!
        assertEquals(0, message.remoteImeBatchEdit.imeCounter)
        assertEquals(0, message.remoteImeBatchEdit.fieldCounter)
        assertEquals("X", message.remoteImeBatchEdit.getEditInfo(0).textFieldStatus.value)
        assertEquals(3, message.remoteImeBatchEdit.getEditInfo(0).textFieldStatus.start)
    }

    @Test fun counterAcknowledgementDoesNotInvalidateQueuedTyping() {
        val keyboard = TvKeyboard()
        keyboard.receive(session("abc", counter = 9))
        val epoch = keyboard.snapshot().epoch
        keyboard.receive(RemoteMessage.newBuilder().setRemoteImeBatchEdit(
            RemoteImeBatchEdit.newBuilder().setImeCounter(0).setFieldCounter(1),
        ).build())
        assertEquals(epoch, keyboard.snapshot().epoch)
        assertEquals("abc", keyboard.snapshot().value.text)
        assertNotNull(keyboard.edit(epoch, KeyboardText("abcd")))
    }

    @Test fun backspaceWithEmptyLocalTextStillSendsTvDelete() {
        val keyboard = TvKeyboard()
        val message = keyboard.backspace(keyboard.snapshot().epoch)!!
        assertEquals(67, message.remoteKeyInject.keyCodeValue)
        assertEquals(RemoteDirection.SHORT, message.remoteKeyInject.direction)
        val epoch = keyboard.snapshot().epoch
        keyboard.reset()
        assertNull(keyboard.backspace(epoch))
    }

    @Test fun appendingAndUnicodeReplacementsNeverReadOutsideTheString() {
        val samples = listOf("", "a", "ab", "abc", "Привет", "🌍", "🌎", "a🌍z", "a🌎z", "e\u0301")
        for (before in samples) for (after in samples) {
            val edit = TvKeyboard.difference(before, after)
            assertEquals(after, before.replaceRange(edit.start, edit.end, edit.text))
            assertFalse(edit.start in 1 until before.length && Character.isLowSurrogate(before[edit.start]))
            assertFalse(edit.end in 1 until before.length && Character.isLowSurrogate(before[edit.end]))
        }
    }

    @Test fun delayedTvEchoDoesNotRollBackNewerTyping() {
        val keyboard = TvKeyboard()
        keyboard.receive(session(""))
        val epoch = keyboard.snapshot().epoch
        keyboard.edit(epoch, KeyboardText("a"), 1)
        keyboard.edit(epoch, KeyboardText("ab"), 2)
        keyboard.receive(RemoteMessage.newBuilder().setRemoteImeShowRequest(
            RemoteImeShowRequest.newBuilder().setRemoteTextFieldStatus(field("a", counter = 5)),
        ).build())
        assertEquals("ab", keyboard.snapshot().value.text)
        assertEquals(2L, keyboard.snapshot().revision)
        assertEquals(epoch, keyboard.snapshot().epoch)
    }

}
