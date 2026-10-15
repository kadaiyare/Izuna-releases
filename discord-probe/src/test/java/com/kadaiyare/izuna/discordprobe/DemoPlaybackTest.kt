package com.kadaiyare.izuna.discordprobe

import org.junit.Assert.*
import org.junit.Test

class DemoPlaybackTest {
    private fun demo() = DemoPlayback(false, true, true, 1000)

    @Test fun elapsedTimeAdvancesPositionWithoutRpcUpdates() {
        assertEquals(50_000, demo().positionAt(6000))
    }

    @Test fun pauseFreezesPositionWhileClockKeepsAdvancing() {
        val paused = demo().pause(6000)
        assertFalse(paused.isPlaying)
        assertEquals(50_000, paused.positionAt(1_000_000))
        assertEquals(paused.positionMs, paused.pause(1_000_000).positionMs)
    }

    @Test fun resumeExcludesTimeSpentPaused() {
        val resumed = demo().pause(6000).resume(60_000)
        assertEquals(51_000, resumed.positionAt(61_000))
        assertTrue(resumed.listening)
        assertTrue(resumed.musicDetails)
    }

    @Test fun duplicateResumeCannotRewindPosition() {
        val active = demo()
        assertSame(active, active.resume(6000))
    }

    @Test fun playingSeekCreatesANewAnchor() {
        val sought = demo().seek(90_000, 20_000)
        assertEquals(91_000, sought.positionAt(21_000))
        val timestamps = PresenceTimestamps.fromPlayback(1_800_000_000_000, sought.positionAt(21_000), DemoPlayback.DURATION_MS)!!
        assertEquals(89_000, timestamps.endMs - 1_800_000_000_000)
    }

    @Test fun pausedSeekRemainsPausedUntilExplicitResume() {
        val sought = demo().pause(6000).seek(90_000, 20_000)
        assertFalse(sought.isPlaying)
        assertEquals(90_000, sought.positionAt(100_000))
        assertEquals(91_000, sought.resume(100_000).positionAt(101_000))
    }

    @Test fun outOfRangeSeekStaysWithinTrack() {
        assertEquals(0, demo().seek(-1, 1000).positionMs)
        assertEquals(DemoPlayback.DURATION_MS, demo().seek(Long.MAX_VALUE, 1000).positionMs)
    }

    @Test fun endingCannotRunPastDurationOrOverflow() {
        assertEquals(DemoPlayback.DURATION_MS, demo().positionAt(Long.MAX_VALUE))
        val ending = demo().seek(177_000, 1000)
        assertEquals(179_999, ending.positionAt(3999))
        assertEquals(DemoPlayback.DURATION_MS, ending.positionAt(4000))
    }

    @Test fun backwardsMonotonicClockDoesNotRewind() {
        assertEquals(45_000, demo().positionAt(500))
    }

    @Test fun timestampsUseElapsedPositionAndFreshWallClock() {
        val paused = demo().pause(6000)
        val now = 1_800_000_000_000L
        val resumed = paused.resume(100_000)
        val timestamps = PresenceTimestamps.fromPlayback(now, resumed.positionAt(101_000), DemoPlayback.DURATION_MS)!!
        assertEquals(now - 51_000, timestamps.startMs)
        assertEquals(now + 129_000, timestamps.endMs)
    }
}
