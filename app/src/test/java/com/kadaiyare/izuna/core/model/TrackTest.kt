package com.kadaiyare.izuna.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {
    @Test fun albumArtistGroupingFallsBackWithoutChangingSongCredits() {
        val track = Track("1", "content://media/1", "曲", "ゲスト歌手", "アルバム", 1000, null, 1, 1,
            albumArtist = " アルバム名義 ")
        assertEquals("ゲスト歌手", track.libraryArtist(false))
        assertEquals("アルバム名義", track.libraryArtist(true))
        listOf(null, "", "  ", "<unknown>").forEach {
            assertEquals("ゲスト歌手", track.copy(albumArtist = it).libraryArtist(true))
        }
        assertEquals("ゲスト歌手", track.artist)
    }
    @Test fun recognizesFlacEvenWhenMediaStoreHasNoMimeType() {
        assertTrue(isFlac(null, "01 - 音楽.FLAC"))
        assertTrue(isFlac("audio/x-flac", "no extension"))
        assertTrue(isFlac("audio/flac", null))
        assertFalse(isFlac("audio/mpeg", "track.mp3"))
        assertFalse(isFlac(null, null))
    }

    @Test fun missingTagsFallBackWithoutReplacingRealNames() {
        assertEquals("不明", normalizedTag("<unknown>", "不明"))
        assertEquals("不明", normalizedTag("  ", "不明"))
        assertEquals("不明", normalizedTag(null, "不明"))
        assertEquals("歌手", normalizedTag(" 歌手 ", "不明"))
    }

    @Test fun formatsLongAndUnknownDurations() {
        assertEquals("0:00", formatDuration(-1))
        assertEquals("4:01", formatDuration(241_999))
        assertEquals("60:00", formatDuration(3_600_000))
    }
}
