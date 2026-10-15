package com.kadaiyare.izuna

import com.kadaiyare.izuna.data.discord.*
import org.junit.Assert.*
import org.junit.Test

class DiscordDisplaySettingsTest {
    @Test fun defaultsPreserveExistingDisplayAndOmitAlbum() {
        assertEquals(DiscordDisplayText("曲", "歌手", "Izuna"), DiscordDisplaySettings().format("曲", "歌手", "アルバム"))
    }
    @Test fun formatsAndCompactTitleUseTheSameFinalText() {
        val options = DiscordDisplaySettings(true, DiscordTitleFormat.ARTIST_SONG, DiscordCompactDisplay.TITLE)
        assertEquals(DiscordDisplayText("歌手 · 曲", "歌手 · アルバム", "歌手 · 曲"), options.format("曲", "歌手", "アルバム"))
        assertEquals("曲 · 歌手", options.copy(titleFormat = DiscordTitleFormat.SONG_ARTIST).format("曲", "歌手", null).title)
    }
    @Test fun missingAlbumAndLineBreaksDoNotProduceAnEmptySeparator() {
        val options = DiscordDisplaySettings(showAlbum = true)
        assertEquals(DiscordDisplayText("曲 名", "歌手", "Izuna"), options.format(" 曲\n名 ", "歌手", " \t "))
        assertEquals("歌手", options.format("曲", "歌手", null).subtitle)
        assertEquals("歌手", options.format("曲", "歌手", "<unknown>").subtitle)
    }
    @Test fun combinedFieldsAreBoundedWithoutCuttingEmoji() {
        val text = DiscordDisplaySettings(true, DiscordTitleFormat.SONG_ARTIST, DiscordCompactDisplay.TITLE)
            .format("🎵".repeat(127), "歌手".repeat(100), "盤".repeat(200))
        assertEquals(128, text.title.codePointCount(0, text.title.length))
        assertEquals(128, text.subtitle.codePointCount(0, text.subtitle.length))
        assertEquals(text.title, text.compact)
        assertFalse(text.title.endsWith("\uD83C"))
    }
}
