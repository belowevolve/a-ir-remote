package belowevolve.airremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PcDialogNavigationTest {
    @Test
    fun `back reverses nested settings bluetooth QR and code journey`() {
        val root = PcDialogNavigation(PcDialogPage.SETTINGS)
        var nav =
            root
                .open(PcDialogPage.BLUETOOTH)
                .open(PcDialogPage.COMPANION)
                .open(PcDialogPage.COMPANION_CODE)
        for (expected in
            listOf(PcDialogPage.COMPANION, PcDialogPage.BLUETOOTH, PcDialogPage.SETTINGS)) {
            nav = nav.back()
            assertEquals(expected, nav.page)
        }
        assertEquals(root, nav)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `repeated taps do not add duplicate pages or extra Back steps`() {
        val root = PcDialogNavigation(PcDialogPage.BLUETOOTH)
        val nav =
            root
                .open(PcDialogPage.COMPANION)
                .open(PcDialogPage.COMPANION)
                .open(PcDialogPage.COMPANION_CODE)
        assertEquals(root, nav.back().back())
        assertEquals(root, nav.back().back().back())
    }

    @Test
    fun `restored history retains the original settings page`() {
        val nav =
            PcDialogNavigation(PcDialogPage.SETTINGS)
                .open(PcDialogPage.BLUETOOTH)
                .open(PcDialogPage.COMPANION_CODE)
        val restored = PcDialogNavigation(nav.pages.map { PcDialogPage.valueOf(it.name) })
        assertEquals(PcDialogPage.SETTINGS, restored.back().back().page)
    }
}
