package belowevolve.airremote

import org.junit.Assert.*
import org.junit.Test

class VoiceActionPlanTest {
    @Test fun onlyTvCommandTogglesAndExplicitPowerIsIdempotent() {
        for (screenOn in listOf(false, true)) {
            assertEquals(!screenOn, VoiceActionPlan.forCommand(LocalVoiceCommand.TV_TOGGLE, screenOn).awake)
            assertEquals(true, VoiceActionPlan.forCommand(LocalVoiceCommand.TV_ON, screenOn).awake)
            assertEquals(false, VoiceActionPlan.forCommand(LocalVoiceCommand.TV_OFF, screenOn).awake)
        }
    }

    @Test fun everyLaunchWakesBeforeOpeningItsFixedTarget() {
        for (screenOn in listOf(false, true)) {
            for ((command, route) in mapOf(LocalVoiceCommand.FILM to "film",
                LocalVoiceCommand.YOUTUBE to "youtube", LocalVoiceCommand.PC to "pc")) {
                val plan = VoiceActionPlan.forCommand(command, screenOn, airHome = true)
                assertEquals(true, plan.awake)
                assertEquals("airhome://remote/$route", plan.appLink)
            }
        }
        assertNull(VoiceActionPlan.forCommand(LocalVoiceCommand.STOP_LISTENING, false).awake)
    }

    @Test fun mediaAndVolumeKeysDoNotWakeOrNavigateBack() {
        for ((command, code) in mapOf(LocalVoiceCommand.OK to 23, LocalVoiceCommand.PAUSE to 127, LocalVoiceCommand.PLAY to 126,
            LocalVoiceCommand.NEXT to 87, LocalVoiceCommand.PREVIOUS to 88,
            LocalVoiceCommand.VOLUME_UP to 24, LocalVoiceCommand.VOLUME_DOWN to 25)) {
            val plan = VoiceActionPlan.forCommand(command, false)
            assertNull(plan.awake)
            assertNull(plan.appLink)
            assertEquals(code, plan.keyCode)
        }
        assertNull(VoiceActionPlan.forCommand(LocalVoiceCommand.VOLUME_SET, false, airHome = true).awake)
    }

    @Test fun ordinaryTvPowerAndMediaNeverSendLauncherLinks() {
        for (screenOn in listOf(false, true)) {
            for (command in listOf(LocalVoiceCommand.TV_TOGGLE, LocalVoiceCommand.TV_ON, LocalVoiceCommand.TV_OFF,
                LocalVoiceCommand.OK, LocalVoiceCommand.PAUSE, LocalVoiceCommand.PLAY, LocalVoiceCommand.NEXT, LocalVoiceCommand.PREVIOUS,
                LocalVoiceCommand.VOLUME_UP, LocalVoiceCommand.VOLUME_DOWN)) {
                val plan = VoiceActionPlan.forCommand(command, screenOn)
                assertNull("$command unexpectedly requires an app", plan.appLink)
                assertNull("$command unexpectedly calls Air Home", plan.helperLink)
            }
        }
        assertEquals("https://www.youtube.com/tv", VoiceActionPlan.forCommand(LocalVoiceCommand.YOUTUBE, false).appLink)
    }

    @Test fun extensionsAreExplicitAndUnavailableCommandsFailBeforePowerChanges() {
        for (command in listOf(LocalVoiceCommand.FILM, LocalVoiceCommand.PC, LocalVoiceCommand.VOLUME_SET, LocalVoiceCommand.SEEK))
            assertThrows(IllegalStateException::class.java) { VoiceActionPlan.forCommand(command, false) }
        assertEquals("airhome://remote/wake", VoiceActionPlan.forCommand(LocalVoiceCommand.PLAY, true, airHome = true).helperLink)
        assertNull(VoiceActionPlan.forCommand(LocalVoiceCommand.TV_OFF, true, airHome = true).helperLink)
        assertNull(VoiceActionPlan.forCommand(LocalVoiceCommand.TV_TOGGLE, true, airHome = true).helperLink)
        val seek = VoiceActionPlan.forCommand(LocalVoiceCommand.SEEK, false, airHome = true)
        assertNull(seek.awake)
        assertNull(seek.helperLink)
        assertNull(seek.keyCode)
    }

    @Test fun backSendsExactlyOneNativeBackWithOrWithoutAirHome() {
        for (airHome in listOf(false, true)) for (screenOn in listOf(false, true)) {
            val back = VoiceActionPlan.forCommand(LocalVoiceCommand.BACK, screenOn, airHome)
            assertEquals(4, back.keyCode)
            assertNull(back.awake)
            assertNull(back.appLink)
            assertNull(back.helperLink)
        }
    }
}
