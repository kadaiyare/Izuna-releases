package com.kadaiyare.izuna.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryGroupsTest {
    private fun track(id: String, album: String, artist: String = "Band", albumId: Long = id.toLong(),
        number: Int = 1, disc: Int = 1) =
        Track(id, "test://$id", id, artist, album, 1000, null, number, disc, albumId)

    @Test fun albumNamesSortIgnoringCaseAndWidthRatherThanSourceOrIdOrder() {
        val tracks = listOf(track("1", "Ｚｅｂｒａ"), track("2", "beta"), track("3", "Apple"))
        assertEquals(listOf("3", "2", "1"), libraryAlbums(tracks).map { it.key.removePrefix("id:") })
        assertEquals(libraryAlbums(tracks), libraryAlbums(tracks.reversed()))
    }

    @Test fun artistNamesSortIndependentlyOfTheirAlbumNames() {
        val tracks = listOf(track("1", "Apple", "Zulu"), track("2", "Middle", "beta"),
            track("3", "Zebra", "Alpha"))
        assertEquals(listOf("Alpha", "beta", "Zulu"), libraryArtists(tracks, false).map { it.name })
    }

    @Test fun japaneseNamesUseNameOrderAndEqualNamesKeepDistinctAlbums() {
        val tracks = listOf(track("1", "うた"), track("3", "あさ"), track("2", "いろ"), track("4", "あさ"))
        assertEquals(listOf("id:3", "id:4", "id:2", "id:1"), libraryAlbums(tracks).map { it.key })
        assertEquals(libraryAlbums(tracks), libraryAlbums(tracks.reversed()))
    }

    @Test fun compilationAlbumKeepsCreditsAndOrdersDiscsAndMissingNumbers() {
        val tracks = listOf(track("1", "Compilation", "Guest A", 10, 1, 2),
            track("2", "Compilation", "Guest B", 10, 0), track("3", "Compilation", "Guest C", 10, 2),
            track("4", "Compilation", "Guest D", 10, 1))
        val album = libraryAlbums(tracks).single()
        assertEquals(listOf("4", "3", "2", "1"), album.tracks.map { it.id })
        assertEquals(listOf("Guest D", "Guest C", "Guest B", "Guest A"), album.tracks.map { it.artist })
    }

    @Test fun albumArtistNamesAreSortedWithSongArtistFallback() {
        val tracks = listOf(track("1", "A", "Guest").copy(albumArtist = "Zulu"),
            track("2", "B", "Alpha").copy(albumArtist = " "),
            track("3", "C", "Other").copy(albumArtist = "beta"))
        assertEquals(listOf("Alpha", "beta", "Zulu"), libraryArtists(tracks, true).map { it.name })
    }
}
