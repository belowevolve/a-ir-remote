package belowevolve.airremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalVoiceCommandTest {
    @Test fun acceptsOnlyCompleteAddressedCommands() {
        assertEquals(LocalVoiceCommand.TV_TOGGLE, LocalVoiceCommand.parse("Эйр, ТВ!"))
        assertEquals(LocalVoiceCommand.TV_TOGGLE, LocalVoiceCommand.parse("эйр тэ вэ"))
        assertEquals(LocalVoiceCommand.FILM, LocalVoiceCommand.parse("  эйр   фильм  "))
        assertEquals(LocalVoiceCommand.YOUTUBE, LocalVoiceCommand.parse("Эйр ютуб"))
        assertEquals(LocalVoiceCommand.YOUTUBE, LocalVoiceCommand.parse("эйр ютьюб"))
        assertNull(LocalVoiceCommand.parse("эйр пк"))
        assertNull(LocalVoiceCommand.parse("эйр пэ ка"))
        assertEquals(LocalVoiceCommand.TV_ON, LocalVoiceCommand.parse("эйр включи телевизор"))
        assertEquals(LocalVoiceCommand.TV_OFF, LocalVoiceCommand.parse("эйр выключи телевизор"))
        assertEquals(LocalVoiceCommand.PC, LocalVoiceCommand.parse("эйр комп"))
        assertEquals(LocalVoiceCommand.PAUSE, LocalVoiceCommand.parse("эйр стоп"))
        assertEquals(LocalVoiceCommand.PAUSE, LocalVoiceCommand.parse("эйр пауза"))
        assertEquals(LocalVoiceCommand.OK, LocalVoiceCommand.parse("Эйр, ок"))
        assertEquals(LocalVoiceCommand.OK, LocalVoiceCommand.parse("эйр окей"))
        assertEquals(LocalVoiceCommand.BACK, LocalVoiceCommand.parse("Эйр, назад!"))
        assertNull(LocalVoiceCommand.parse("эйр не назад"))
        assertNull(LocalVoiceCommand.parse("эйр назад не надо"))
        assertEquals(LocalVoiceCommand.PLAY, LocalVoiceCommand.parse("эйр плей"))
        assertEquals(LocalVoiceCommand.PLAY, LocalVoiceCommand.parse("эйр продолжай"))
        assertEquals(LocalVoiceCommand.PLAY, LocalVoiceCommand.parse("эйр дальше"))
        assertEquals(LocalVoiceCommand.NEXT, LocalVoiceCommand.parse("эйр следующее"))
        assertEquals(LocalVoiceCommand.NEXT, LocalVoiceCommand.parse("эйр следующий"))
        assertNull(LocalVoiceCommand.parse("эйр следующий ролик"))
        assertEquals(LocalVoiceCommand.PREVIOUS, LocalVoiceCommand.parse("эйр прошлое"))
        assertEquals(LocalVoiceCommand.VOLUME_DOWN, LocalVoiceCommand.parse("эйр тише"))
        assertEquals(LocalVoiceCommand.VOLUME_UP, LocalVoiceCommand.parse("эйр громче"))
        assertEquals(LocalVoiceCommand.STOP_LISTENING, LocalVoiceCommand.parse("эйр хватит слушать"))
        listOf("фильм", "тв", "пк", "эйр", "эйр не включай телевизор", "эйр пока",
            "он сказал эйр фильм", "эйр фильм не надо", "пульт включи телевизор", "[unk]", "").forEach {
            assertNull("Unexpected command: $it", LocalVoiceCommand.parse(it))
        }
    }

    @Test fun volumeHasAnExactBoundedArgument() {
        assertEquals(VoiceCommandRequest(LocalVoiceCommand.VOLUME_SET, 45), VoiceCommandRequest.parse("Эйр, громкость сорок пять"))
        assertEquals(VoiceCommandRequest(LocalVoiceCommand.VOLUME_SET, 0), VoiceCommandRequest.parse("эйр громкость ноль"))
        assertEquals(VoiceCommandRequest(LocalVoiceCommand.VOLUME_SET, 100), VoiceCommandRequest.parse("эйр громкость 100"))
        for (number in 0..100) assertEquals(number, SpokenVolume.parse(SpokenVolume.words(number)))
        for (text in listOf("эйр громкость", "эйр громкость 101", "эйр громкость сто один", "эйр громкость минус один",
            "эйр громкость сорок пять не надо", "громкость двадцать", "эйр громкость двадцать двадцать"))
            assertNull(text, VoiceCommandRequest.parse(text))
    }

    @Test fun relativeVolumeKeepsItsStepCountAndRejectsNegationsOrOutOfRangeCounts() {
        assertEquals(2, VoiceCommandRequest.parse("Эйр, громче 2")?.volumeSteps)
        assertEquals(3, VoiceCommandRequest.parse("эйр тише три")?.volumeSteps)
        assertEquals(1, VoiceCommandRequest.parse("эйр громче")?.volumeSteps)
        assertEquals(1, VoiceCommandRequest.parse("эйр тише")?.volumeSteps)
        for (command in listOf(LocalVoiceCommand.VOLUME_UP, LocalVoiceCommand.VOLUME_DOWN)) for (steps in 1..20) {
            val request = VoiceCommandRequest(command, volumeSteps = steps)
            assertEquals(request, VoiceCommandRequest.parse(request.transcript))
        }
        for (phrase in listOf("громче два", "эйр громче ноль", "эйр тише 21", "эйр тише сто",
            "эйр громче минус два", "эйр не громче два", "эйр тише три не надо", "эйр громче два три"))
            assertNull(phrase, VoiceCommandRequest.parse(phrase))
    }

    @Test fun seekIsRelativeBoundedAndRejectsIncompleteOrNegatedSpeech() {
        assertEquals(900, VoiceCommandRequest.parse("Эйр, перемотай 15 минут")?.seekSeconds)
        assertEquals(-300, VoiceCommandRequest.parse("эйр перемотай пять минут назад")?.seekSeconds)
        assertEquals(21, VoiceCommandRequest.parse("эйр перемотай на двадцать одну секунду вперед")?.seekSeconds)
        assertEquals(120, VoiceCommandRequest.parse("эйр перемотай две минуты")?.seekSeconds)
        assertEquals(-300, VoiceCommandRequest.parse("Эйр, отмотай пять минут")?.seekSeconds)
        assertEquals(-21, VoiceCommandRequest.parse("эйр отмотай на двадцать одну секунду назад")?.seekSeconds)
        for (phrase in SpokenSeek.recognitionPhrases) {
            val request = checkNotNull(VoiceCommandRequest.parse(phrase)) { phrase }
            assertEquals(request, VoiceCommandRequest.parse(request.transcript))
        }
        for (phrase in listOf("эйр перемотай пять", "эйр перемотай ноль минут",
            "эйр перемотай сто один минуту", "эйр перемотай минус пять минут", "перемотай пять минут",
            "эйр не перемотай пять минут", "эйр перемотай пять минут не надо",
            "эйр перемотай пять минут назад вперед", "эйр перемотай пять минут вперед назад", "эйр перемотай пять минут потом",
            "эйр отмотай пять", "эйр отмотай на", "эйр отмотай ноль секунд", "эйр отмотай сто один минуту",
            "эйр отмотай вперед", "эйр отмотай пять минут вперед", "эйр не отмотай", "эйр отмотай не надо",
            "эйр отмотай пять минут не надо", "эйр не перемотай", "эйр перемотай не надо", "отмотай"))
            assertNull(phrase, VoiceCommandRequest.parse(phrase))
    }

    @Test fun seekWithoutDurationMovesTenSecondsInTheRequestedDirection() {
        assertEquals(10, VoiceCommandRequest.parse("Эйр, перемотай!")?.seekSeconds)
        assertEquals(10, VoiceCommandRequest.parse("эйр перемотай вперёд")?.seekSeconds)
        assertEquals(-10, VoiceCommandRequest.parse("эйр отмотай")?.seekSeconds)
        assertEquals(-10, VoiceCommandRequest.parse("эйр отмотай назад")?.seekSeconds)
        assertEquals(-10, VoiceCommandRequest.parse("эйр перемотай назад")?.seekSeconds)
    }
}
