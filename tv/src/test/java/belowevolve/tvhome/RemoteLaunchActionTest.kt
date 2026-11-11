package belowevolve.tvhome

import org.junit.Assert.*
import org.junit.Test

class RemoteLaunchActionTest {
    @Test fun acceptsOnlyKnownRemoteRoutes() {
        assertEquals(RemoteLaunchAction.FILM, RemoteLaunchAction.fromRoute("airhome", "remote", "/film"))
        assertEquals(RemoteLaunchAction.YOUTUBE, RemoteLaunchAction.fromRoute("airhome", "remote", "/youtube"))
        assertEquals(RemoteLaunchAction.PC, RemoteLaunchAction.fromRoute("airhome", "remote", "/pc"))
        assertEquals(RemoteLaunchAction.VOLUME, RemoteLaunchAction.fromRoute("airhome", "remote", "/volume"))
        assertEquals(RemoteLaunchAction.SEEK, RemoteLaunchAction.fromRoute("airhome", "remote", "/seek"))
        assertEquals(RemoteLaunchAction.WAKE, RemoteLaunchAction.fromRoute("airhome", "remote", "/wake"))
        assertNull(RemoteLaunchAction.fromRoute("https", "remote", "/film"))
        assertNull(RemoteLaunchAction.fromRoute("airhome", "other", "/pc"))
        assertNull(RemoteLaunchAction.fromRoute("airhome", "remote", "/arbitrary.app"))
        assertNull(RemoteLaunchAction.fromRoute(null, null, null))
    }

    @Test fun rejectsInvalidVolumeArguments() {
        assertEquals(0, RemoteLaunchAction.volumePercent("0"))
        assertEquals(45, RemoteLaunchAction.volumePercent("45"))
        assertEquals(100, RemoteLaunchAction.volumePercent("100"))
        for (invalid in listOf(null, "-1", "101", "1.5", "", "loud")) assertNull(RemoteLaunchAction.volumePercent(invalid))
    }

    @Test fun seekRejectsZeroOverflowAndUnboundedOffsets() {
        assertEquals(-300, RemoteLaunchAction.seekSeconds("-300"))
        assertEquals(900, RemoteLaunchAction.seekSeconds("900"))
        for (invalid in listOf(null, "0", "-6001", "6001", "2147483648", "1.5", ""))
            assertNull(RemoteLaunchAction.seekSeconds(invalid))
    }
}
