package com.kadaiyare.izuna

import com.kadaiyare.izuna.data.discord.PresenceSong
import org.junit.Assert.*
import org.junit.Test

class PresenceSongTest {
    @Test fun workerWaitAdvancesPosition() {
        val song = PresenceSong("曲", "歌手", 45_000, 180_000, 1000)
        assertEquals(950_000L to 1_130_000L, song.timestamps(1_000_000, 6000))
    }
    @Test fun unknownDurationHasNoInventedTimer() {
        val song = PresenceSong("曲", "歌手", 100, -1, 1000)
        assertEquals(0L to 0L, song.timestamps(1_000_000, 6000))
        assertFalse(song.ended(Long.MAX_VALUE))
    }
    @Test fun endedTrackIsNotPublishedWithAnExpiredTimer() {
        val song = PresenceSong("曲", "歌手", 177_000, 180_000, 1000)
        assertFalse(song.ended(3999))
        assertTrue(song.ended(4000))
        assertEquals(0L to 0L, song.timestamps(1_000_000, 4000))
    }
    @Test fun invalidPositionAndOverflowHaveNoTimer() {
        assertEquals(0L to 0L, PresenceSong("曲", "歌手", -1, 180_000, 1000).timestamps(1_000_000, 1000))
        assertEquals(0L to 0L, PresenceSong("曲", "歌手", 0, 180_000, 1000).timestamps(Long.MAX_VALUE, 1000))
    }
}
