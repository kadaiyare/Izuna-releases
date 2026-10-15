package com.kadaiyare.izuna.data.playback

import com.kadaiyare.izuna.core.model.PlaybackSnapshot
import com.kadaiyare.izuna.core.model.Track
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UTFDataFormatException

class InvalidPlaybackSnapshot(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Versioned, bounded encoding; Android-free so malformed checkpoints can be tested on the JVM. */
object PlaybackSnapshotCodec {
    private const val MAGIC = 0x495a554e // IZUN
    private const val VERSION = 1
    private const val MAX_TRACKS = 10_000
    private const val MAX_TEXT = 4_096

    fun read(input: InputStream): PlaybackSnapshot {
        try {
            val data = DataInputStream(input)
            checkFormat(data.readInt() == MAGIC && data.readInt() == VERSION, "Unknown snapshot version")
            val count = data.readInt()
            checkFormat(count in 0..MAX_TRACKS, "Invalid queue size")
            val index = data.readInt()
            val position = data.readLong()
            val shuffle = data.readBoolean()
            val repeat = data.readInt()
            checkFormat((count == 0 && index == 0) || index in 0 until count, "Invalid current index")
            checkFormat(position >= 0 && repeat in 0..2, "Invalid playback mode or position")
            val tracks = List(count) {
                val uri = data.readText()
                checkFormat(uri.startsWith("content://media/"), "Invalid media URI")
                Track(uri, uri, data.readText(), data.readText(), data.readText(), data.readLong(),
                    if (data.readBoolean()) data.readText() else null, data.readInt(), data.readInt()).also {
                    checkFormat(it.durationMs >= 0 && it.trackNumber >= 0 && it.discNumber >= 0, "Invalid track values")
                }
            }
            checkFormat(data.read() == -1, "Unexpected trailing data")
            return PlaybackSnapshot(tracks, index, position, shuffle, repeat)
        } catch (error: EOFException) {
            throw InvalidPlaybackSnapshot("Truncated snapshot", error)
        } catch (error: UTFDataFormatException) {
            throw InvalidPlaybackSnapshot("Invalid snapshot text", error)
        }
    }

    fun write(snapshot: PlaybackSnapshot, output: OutputStream) {
        checkFormat(snapshot.tracks.size <= MAX_TRACKS, "Queue exceeds checkpoint limit")
        checkFormat((snapshot.tracks.isEmpty() && snapshot.currentIndex == 0) || snapshot.currentIndex in snapshot.tracks.indices,
            "Invalid current index")
        checkFormat(snapshot.positionMs >= 0 && snapshot.repeatMode in 0..2, "Invalid playback values")
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeInt(snapshot.tracks.size)
        data.writeInt(snapshot.currentIndex)
        data.writeLong(snapshot.positionMs)
        data.writeBoolean(snapshot.shuffle)
        data.writeInt(snapshot.repeatMode)
        snapshot.tracks.forEach { track ->
            checkFormat(track.id == track.uri && track.uri.startsWith("content://media/"), "Invalid media URI")
            checkFormat(track.uri.length <= MAX_TEXT, "Media URI too long")
            checkFormat(track.durationMs >= 0 && track.trackNumber >= 0 && track.discNumber >= 0, "Invalid track values")
            data.writeUTF(track.uri)
            data.writeUTF(track.title.take(MAX_TEXT))
            data.writeUTF(track.artist.take(MAX_TEXT))
            data.writeUTF(track.album.take(MAX_TEXT))
            data.writeLong(track.durationMs)
            data.writeBoolean(track.artworkUri != null)
            track.artworkUri?.let { checkFormat(it.length <= MAX_TEXT, "Artwork URI too long"); data.writeUTF(it) }
            data.writeInt(track.trackNumber)
            data.writeInt(track.discNumber)
        }
        data.flush()
    }

    private fun DataInputStream.readText(): String = readUTF().also { checkFormat(it.length <= MAX_TEXT, "Text too long") }
    private fun checkFormat(valid: Boolean, message: String) { if (!valid) throw InvalidPlaybackSnapshot(message) }
}
