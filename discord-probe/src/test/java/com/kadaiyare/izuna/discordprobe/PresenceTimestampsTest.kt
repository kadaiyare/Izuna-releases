package com.kadaiyare.izuna.discordprobe

import org.junit.Assert.*
import org.junit.Test

class PresenceTimestampsTest {
    private val now = 1_791_532_800_123L

    @Test fun halfwayPlaybackKeepsMillisecondPrecision() {
        val result = PresenceTimestamps.fromPlayback(now, 45_000, 180_000)!!
        assertEquals(now - 45_000, result.startMs)
        assertEquals(now + 135_000, result.endMs)
        assertEquals(180_000, result.endMs - result.startMs)
    }

    @Test fun freshPlaybackStartsNow() {
        assertEquals(PresenceTimestamps(now, now + 180_000), PresenceTimestamps.fromPlayback(now, 0, 180_000))
    }

    @Test fun finishedTrackDoesNotInventFutureTime() {
        assertNull(PresenceTimestamps.fromPlayback(now, 180_000, 180_000))
        assertNull(PresenceTimestamps.fromPlayback(now, 180_001, 180_000))
    }

    @Test fun unknownDurationOrInvalidPositionHasNoTimer() {
        assertNull(PresenceTimestamps.fromPlayback(now, 0, 0))
        assertNull(PresenceTimestamps.fromPlayback(now, 0, -1))
        assertNull(PresenceTimestamps.fromPlayback(now, -1, 180_000))
    }

    @Test fun invalidClockCannotCreateUnsignedEpoch() {
        assertNull(PresenceTimestamps.fromPlayback(0, 0, 180_000))
        assertNull(PresenceTimestamps.fromPlayback(45_000, 45_000, 180_000))
        assertNull(PresenceTimestamps.fromPlayback(-1, 0, 180_000))
    }

    @Test fun endTimeOverflowHasNoTimer() {
        assertNull(PresenceTimestamps.fromPlayback(Long.MAX_VALUE, 0, 1))
        assertEquals(PresenceTimestamps(Long.MAX_VALUE - 1, Long.MAX_VALUE),
            PresenceTimestamps.fromPlayback(Long.MAX_VALUE - 1, 0, 1))
    }
}
