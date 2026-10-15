package com.kadaiyare.izuna.core.model

data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUri: String?,
    val trackNumber: Int,
    val discNumber: Int,
    val albumId: Long? = null,
    val albumArtist: String? = null,
)

/** Library grouping only; song credits and external sharing still use artist. */
fun Track.libraryArtist(useAlbumArtist: Boolean): String =
    if (useAlbumArtist) normalizedTag(albumArtist, artist) else artist

fun normalizedTag(value: String?, fallback: String): String =
    value?.trim()?.takeUnless { it.isEmpty() || it == "<unknown>" } ?: fallback

fun isFlac(mimeType: String?, displayName: String?): Boolean =
    mimeType.equals("audio/flac", ignoreCase = true) ||
        mimeType.equals("audio/x-flac", ignoreCase = true) ||
        displayName?.endsWith(".flac", ignoreCase = true) == true

fun formatDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
