package com.kadaiyare.izuna.core.model

/** MediaStore identity distinguishes equal album names and keeps compilation artists together. */
fun Track.albumKey(): String = albumId?.takeIf { it > 0 }?.let { "id:$it" }
    ?: "tags:$album\u0000$artist"

internal val albumTrackOrder = compareBy<Track> { it.discNumber.takeIf { disc -> disc > 0 } ?: 1 }
    .thenBy { it.trackNumber.takeIf { number -> number > 0 } ?: Int.MAX_VALUE }
    .thenBy { it.title }

fun albumQueueFor(tracks: List<Track>, currentId: String?): List<Track> {
    val current = tracks.firstOrNull { it.id == currentId } ?: return emptyList()
    if (current.album.isBlank() || current.album == "アルバム不明") return emptyList()
    return tracks.filter { it.albumKey() == current.albumKey() }
        .sortedWith(albumTrackOrder)
}
