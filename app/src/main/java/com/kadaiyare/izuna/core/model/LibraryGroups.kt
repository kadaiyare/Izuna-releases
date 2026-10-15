package com.kadaiyare.izuna.core.model

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

data class LibraryGroup(val key: String, val name: String, val tracks: List<Track>)

fun libraryAlbums(tracks: List<Track>): List<LibraryGroup> =
    sortLibraryGroups(tracks.groupBy(Track::albumKey).map { (key, songs) ->
        LibraryGroup(key, songs.first().album, songs.sortedWith(albumTrackOrder))
    })

fun libraryArtists(tracks: List<Track>, useAlbumArtist: Boolean): List<LibraryGroup> =
    sortLibraryGroups(tracks.groupBy { it.libraryArtist(useAlbumArtist) }.map { (name, songs) ->
        LibraryGroup(name, name, songs)
    })

private fun sortLibraryGroups(groups: List<LibraryGroup>): List<LibraryGroup> {
    // Keep ordering independent of MediaStore insertion order and the device's UI language.
    val collator = Collator.getInstance(Locale.JAPANESE).apply { strength = Collator.SECONDARY }
    val names = groups.associate { it.key to Normalizer.normalize(it.name.trim(), Normalizer.Form.NFKC) }
    return groups.sortedWith { left, right ->
        collator.compare(names.getValue(left.key), names.getValue(right.key)).takeIf { it != 0 }
            ?: left.name.compareTo(right.name).takeIf { it != 0 }
            ?: left.key.compareTo(right.key)
    }
}
