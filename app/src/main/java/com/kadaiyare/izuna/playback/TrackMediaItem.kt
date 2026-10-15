package com.kadaiyare.izuna.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.kadaiyare.izuna.core.model.Track

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(uri)
    .setMimeType("audio/flac")
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumArtist(albumArtist)
            .setAlbumTitle(album)
            .setDurationMs(durationMs)
            .setArtworkUri(artworkUri?.toUri())
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build(),
    )
    .build()

internal fun MediaItem.toStoredTrack(): Track {
    val metadata = mediaMetadata
    val uri = localConfiguration?.uri?.toString() ?: mediaId
    return Track(uri, uri, metadata.title?.toString() ?: "タイトル不明", metadata.artist?.toString() ?: "アーティスト不明",
        metadata.albumTitle?.toString() ?: "アルバム不明", metadata.durationMs?.coerceAtLeast(0) ?: 0,
        metadata.artworkUri?.toString(), metadata.trackNumber?.coerceAtLeast(0) ?: 0, 0,
        albumArtist = metadata.albumArtist?.toString())
}
