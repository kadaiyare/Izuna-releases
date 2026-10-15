package com.kadaiyare.izuna.data.playback

import com.kadaiyare.izuna.core.model.PlaybackSnapshot
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.core.model.withAvailableTracks
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackSnapshotTest {
    private fun track(id: Int) = Track("content://media/external/audio/media/$id", "content://media/external/audio/media/$id",
        "曲 $id 🎵", "歌手", "アルバム", 12_000, if (id == 1) "content://media/external/audio/albumart/4" else null, id, 1)
    private val queue = listOf(track(1), track(2), track(3))
    private fun encode(value: PlaybackSnapshot) = ByteArrayOutputStream().also { PlaybackSnapshotCodec.write(value, it) }.toByteArray()
    private fun decode(bytes: ByteArray) = PlaybackSnapshotCodec.read(ByteArrayInputStream(bytes))

    @Test fun roundTripsQueueUnicodeArtworkModesAndPosition() {
        val snapshot = PlaybackSnapshot(queue, 1, 4_500, true, 2)
        assertEquals(snapshot, decode(encode(snapshot)))
        assertEquals(PlaybackSnapshot(), decode(encode(PlaybackSnapshot())))
    }

    @Test fun rejectsEveryTruncatedSnapshot() {
        val bytes = encode(PlaybackSnapshot(queue, 1, 4_500))
        for (size in bytes.indices) {
            assertThrows(InvalidPlaybackSnapshot::class.java) { decode(bytes.copyOf(size)) }
        }
    }

    @Test fun rejectsUnknownVersionHugeQueueAndTrailingBytes() {
        val bytes = encode(PlaybackSnapshot(queue))
        assertThrows(InvalidPlaybackSnapshot::class.java) { decode(bytes.copyOf().apply { this[7] = 2 }) }
        val huge = ByteArrayOutputStream().also { DataOutputStream(it).run { writeInt(0x495a554e); writeInt(1); writeInt(Int.MAX_VALUE) } }
        assertThrows(InvalidPlaybackSnapshot::class.java) { decode(huge.toByteArray()) }
        assertThrows(InvalidPlaybackSnapshot::class.java) { decode(bytes + 1.toByte()) }
    }

    @Test fun rejectsInvalidPositionsModesAndExternalUris() {
        for (snapshot in listOf(PlaybackSnapshot(queue, -1), PlaybackSnapshot(queue, 3), PlaybackSnapshot(queue, positionMs = -1),
                PlaybackSnapshot(queue, repeatMode = 3), PlaybackSnapshot(listOf(queue[0].copy(id = "https://example.com/a", uri = "https://example.com/a"))))) {
            assertThrows(InvalidPlaybackSnapshot::class.java) { encode(snapshot) }
        }
    }

    @Test fun removesMissingEarlierFileWithoutLosingSelectedPosition() {
        val available = queue.drop(1).associateBy { it.id }
        val restored = PlaybackSnapshot(queue, 1, 3_000, true, 1).withAvailableTracks(available)
        assertEquals(PlaybackSnapshot(queue.drop(1), 0, 3_000, true, 1), restored)
    }

    @Test fun selectsNextSurvivorOrLastEarlierSurvivorAtZero() {
        assertEquals(PlaybackSnapshot(listOf(queue[0], queue[2]), 1),
            PlaybackSnapshot(queue, 1, 3_000).withAvailableTracks(listOf(queue[0], queue[2]).associateBy { it.id }))
        assertEquals(PlaybackSnapshot(listOf(queue[0]), 0),
            PlaybackSnapshot(queue, 2, 3_000).withAvailableTracks(listOf(queue[0]).associateBy { it.id }))
    }

    @Test fun keepsDuplicateEntriesAndClampsPositionToUpdatedDuration() {
        val duplicateQueue = listOf(queue[0], queue[0], queue[1])
        val shorter = queue[0].copy(durationMs = 1_000)
        val restored = PlaybackSnapshot(duplicateQueue, 1, 3_000).withAvailableTracks(mapOf(shorter.id to shorter))
        assertEquals(PlaybackSnapshot(listOf(shorter, shorter), 1, 1_000), restored)
    }

    @Test fun emptyLibraryClearsQueueAndPositionButKeepsModes() {
        assertEquals(PlaybackSnapshot(shuffle = true, repeatMode = 2),
            PlaybackSnapshot(queue, 2, 9_000, true, 2).withAvailableTracks(emptyMap()))
    }
}
