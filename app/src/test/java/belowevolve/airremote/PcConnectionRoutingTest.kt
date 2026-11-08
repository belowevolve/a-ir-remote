package belowevolve.airremote

import org.junit.Assert.assertEquals
import org.junit.Test

class PcConnectionRoutingTest {
    @Test fun `missing Bluetooth opens the combined PC connection`() {
        assertEquals(PcDialogPage.BLUETOOTH, pcDevicePage(DeviceKind.PC, false, false))
        assertEquals(PcDialogPage.BLUETOOTH, pcDevicePage(DeviceKind.PC, false, true))
        assertEquals(PcDialogPage.BLUETOOTH, pcDevicePage(DeviceKind.MICROPHONE, false, false))
    }

    @Test fun `microphone needs only companion once Bluetooth is connected`() {
        assertEquals(PcDialogPage.COMPANION, pcDevicePage(DeviceKind.MICROPHONE, true, false))
        assertEquals(PcDialogPage.COMPANION, pcConnectionPage(true))
        assertEquals(PcDialogPage.BLUETOOTH, pcConnectionPage(false))
    }

    @Test fun `settings require the transport used by the active screen`() {
        assertEquals(PcDialogPage.SETTINGS, pcDevicePage(DeviceKind.PC, true, false))
        assertEquals(PcDialogPage.SETTINGS, pcDevicePage(DeviceKind.PC, true, true))
        assertEquals(PcDialogPage.SETTINGS, pcDevicePage(DeviceKind.MICROPHONE, false, true))
        assertEquals(PcDialogPage.SETTINGS, pcDevicePage(DeviceKind.MICROPHONE, true, true))
    }
}
