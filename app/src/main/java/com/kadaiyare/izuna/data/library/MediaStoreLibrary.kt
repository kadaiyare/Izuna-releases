package com.kadaiyare.izuna.data.library

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import androidx.core.net.toUri
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.core.model.isFlac
import com.kadaiyare.izuna.core.model.normalizedTag
import com.kadaiyare.izuna.data.audio.readFlacScrobbleTags
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

class MediaStoreLibrary(private val resolver: ContentResolver) {
    suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.ALBUM_ARTIST,
        )
        // Do not require IS_MUSIC: imported FLAC files can lack this flag.
        val selection = "(${MediaStore.Audio.Media.MIME_TYPE} IN (?, ?) OR " +
            "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?)"
        val tracks = mutableListOf<Track>()
        val cursor = resolver.query(
            collection, projection, selection,
            arrayOf("audio/flac", "audio/x-flac", "%.flac"),
            "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, " +
                "${MediaStore.Audio.Media.TRACK}, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
        ) ?: error("MediaStoreを読み取れませんでした。もう一度スキャンしてください。")
        cursor.use {
            while (it.moveToNext()) {
                ensureActive()
                val name = it.getString(1)
                if (!isFlac(it.getString(6), name)) continue
                val uri = ContentUris.withAppendedId(collection, it.getLong(0)).toString()
                val number = it.getInt(8).coerceAtLeast(0)
                val indexedAlbumArtist = normalizedTag(it.getString(9), "").takeIf(String::isNotEmpty)
                // Some Samsung imports leave metadata unindexed even after scanning.
                // Read bounded native tags only when the index lacks artist information.
                val tags = if (indexedAlbumArtist == null || normalizedTag(it.getString(3), "").isEmpty()) {
                    try { resolver.openInputStream(uri.toUri())?.use(::readFlacScrobbleTags) }
                    catch (_: IOException) { null }
                    catch (_: SecurityException) { null }
                } else null
                tracks += Track(
                    id = uri,
                    uri = uri,
                    title = normalizedTag(it.getString(2), tags?.title ?: name ?: "タイトル不明"),
                    artist = normalizedTag(it.getString(3), tags?.artist ?: "アーティスト不明"),
                    album = normalizedTag(it.getString(4), tags?.album ?: "アルバム不明"),
                    durationMs = it.getLong(5).coerceAtLeast(0),
                    artworkUri = it.getLong(7).takeIf { albumId -> albumId > 0 }?.let { albumId ->
                        ContentUris.withAppendedId(
                            "content://media/external/audio/albumart".toUri(), albumId,
                        ).toString()
                    },
                    trackNumber = number % 1_000,
                    discNumber = number / 1_000,
                    albumId = it.getLong(7).takeIf { albumId -> albumId > 0 },
                    albumArtist = indexedAlbumArtist ?: normalizedTag(tags?.albumArtist, "").takeIf(String::isNotEmpty),
                )
            }
        }
        tracks
    }
}
