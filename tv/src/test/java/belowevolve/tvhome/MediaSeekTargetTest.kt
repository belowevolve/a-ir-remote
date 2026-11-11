package belowevolve.tvhome

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSeekTargetTest {
    @Test fun pausedOffsetsUseThePositionWithoutAddingElapsedTime() {
        assertEquals(MediaSeekTarget(300_000, 1_200_000), MediaSeekTarget.calculate(300_000, 1000, 9000, 1f, false, 1_800_000, 900))
        assertEquals(MediaSeekTarget(1_200_000, 900_000), MediaSeekTarget.calculate(1_200_000, 1000, 9000, 1f, false, 1_800_000, -300))
    }
    @Test fun playingPositionIncludesElapsedTimeAndPlaybackSpeed() {
        assertEquals(MediaSeekTarget(104_000, 119_000), MediaSeekTarget.calculate(100_000, 1000, 3000, 2f, true, 1_800_000, 15))
    }
    @Test fun boundsAndUnknownTimestampNeverWrapOrInventHoursOfPlayback() {
        assertEquals(0L, MediaSeekTarget.calculate(5_000, 0, 500000, 1f, true, 100_000, -30).targetMs)
        assertEquals(100_000L, MediaSeekTarget.calculate(90_000, 500, 500, 1f, false, 100_000, 30).targetMs)
        assertEquals(90_000L, MediaSeekTarget.calculate(90_000, 900, 500, 1f, true, 100_000, -30).beforeMs)
    }
}
