package com.kadaiyare.izuna.core.model

import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun parsesFractionsRepeatedTimesAndSortsWithoutDroppingBlankMarkers() {
        val lines = parseLrc("[ar:Test]\n[00:20.123]Later\n[00:01.2][00:10.25]Repeated\n[00:15.00]\n[00:30]Ending")
        assertEquals(listOf(1200L, 10250L, 15000L, 20123L, 30000L), lines.map { it.timeMs })
        assertEquals(listOf("Repeated", "Repeated", "", "Later", "Ending"), lines.map { it.text })
    }
    @Test fun lrcOffsetShiftsTimesAndInvalidDataIsBounded() {
        assertEquals(500L, parseLrc("[offset:500]\n[00:01.00]Test").single().timeMs)
        assertEquals(1500L, parseLrc("[offset:-500]\n[00:01.00]Test").single().timeMs)
        assertTrue(parseLrc("[00:99]invalid\nnormal lyrics\n[ti:Test]").isEmpty())
        assertTrue(parseLrc("x".repeat(131073)).isEmpty())
        assertTrue(parseLrc(null).isEmpty())
    }
    @Test fun highlightsExactBoundaryAndTracksForwardBackwardSeeksAndPause() {
        val lines = parseLrc("[00:01]First\n[00:05]Second\n[00:10]Third")
        assertEquals(-1, activeLyricIndex(lines, 999))
        assertEquals(0, activeLyricIndex(lines, 1000))
        assertEquals(2, activeLyricIndex(lines, 20000))
        assertEquals(0, activeLyricIndex(lines, 3000))
        repeat(3) { assertEquals(1, activeLyricIndex(lines, 7000)) }
    }
    @Test fun positiveUserAdjustmentDelaysAndNegativeAdjustmentAdvancesLyrics() {
        val lines = parseLrc("[00:01]First\n[00:05]Second")
        assertEquals(0, activeLyricIndex(lines, 5000, 500))
        assertEquals(1, activeLyricIndex(lines, 5500, 500))
        assertEquals(1, activeLyricIndex(lines, 4500, -500))
    }
    @Test fun simultaneousTranslationsShareTheCurrentTimestamp() {
        val lines = parseLrc("[00:01]Original\n[00:01]Translation\n[00:05]Next")
        val active = activeLyricIndex(lines, 2000)
        assertEquals(1, active)
        assertEquals(2, lines.count { it.timeMs == lines[active].timeMs })
    }
    @Test fun queryRejectsUnknownDurationOversizeAndControlCharacters() {
        val query = LyricsQuery("曲", "歌手", "アルバム", 180)
        assertTrue(query.valid())
        assertFalse(query.copy(durationSeconds = 0).valid())
        assertFalse(query.copy(durationSeconds = 3601).valid())
        assertFalse(query.copy(title = " ").valid())
        assertFalse(query.copy(title = "x".repeat(513)).valid())
        assertFalse(query.copy(artist = "Artist\n").valid())
    }
}
