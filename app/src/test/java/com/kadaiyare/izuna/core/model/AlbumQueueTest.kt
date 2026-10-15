package com.kadaiyare.izuna.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumQueueTest {
    private fun track(id: String, albumId: Long? = 10, artist: String = "Artist", disc: Int = 1, number: Int = 1) =
        Track(id, "content://audio/$id", id, artist, "Album", 12_000, null, number, disc, albumId)

    @Test fun keepsCompilationTogetherAndExcludesEqualNamesFromAnotherAlbum() {
        val tracks = listOf(track("a"), track("b", artist = "Guest"), track("c", albumId = 20))
        assertEquals(listOf("a", "b"), albumQueueFor(tracks, "a").map { it.id })
    }

    @Test fun sortsDiscsAndTracksWithUnnumberedTracksLast() {
        val tracks = listOf(track("unknown", number = 0), track("disc2", disc = 2),
            track("second", number = 2), track("first"))
        assertEquals(listOf("first", "second", "unknown", "disc2"), albumQueueFor(tracks, "second").map { it.id })
    }

    @Test fun fallsBackToAlbumAndArtistWhenMediaStoreIdentityIsMissing() {
        val tracks = listOf(track("a", null), track("b", null), track("other", null, artist = "Other"))
        assertEquals(listOf("a", "b"), albumQueueFor(tracks, "a").map { it.id })
    }

    @Test fun doesNotCollectUnrelatedSongsWithoutAnAlbumOrCurrentSong() {
        val tracks = listOf(track("a").copy(album = "アルバム不明"), track("b").copy(album = ""))
        assertTrue(albumQueueFor(tracks, "a").isEmpty())
        assertTrue(albumQueueFor(tracks, "b").isEmpty())
        assertTrue(albumQueueFor(tracks, "missing").isEmpty())
    }
}
