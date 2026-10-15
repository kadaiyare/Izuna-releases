package com.kadaiyare.izuna

import com.kadaiyare.izuna.data.audio.readFlacScrobbleTags
import com.kadaiyare.izuna.data.lastfm.scrobbleRetryDelay
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class FlacCommentReaderTest {
    private fun fixture(vararg comments: String): ByteArray {
        val body = ByteArrayOutputStream()
        fun little(value: Int) { repeat(4) { body.write(value ushr (it * 8) and 255) } }
        little(0); little(comments.size)
        comments.forEach { val bytes = it.toByteArray(Charsets.UTF_8); little(bytes.size); body.write(bytes) }
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(0x664c6143); writeByte(0); write(byteArrayOf(0, 0, 34)); write(ByteArray(34))
                writeByte(132); val size = body.size(); writeByte(size ushr 16); writeByte(size ushr 8); writeByte(size)
                write(body.toByteArray())
            }
        }.toByteArray()
    }
    private fun read(bytes: ByteArray) = readFlacScrobbleTags(ByteArrayInputStream(bytes))

    @Test fun readsCaseInsensitiveUnicodeTagsWithoutFilenameFallback() {
        val tags = read(fixture("title= 夜を歩く ", "ARTIST=じん", "Album=作品集", "COMMENT=extra=equals"))!!
        assertEquals("夜を歩く", tags.title)
        assertEquals("じん", tags.artist)
        assertEquals("作品集", tags.album)
    }
    @Test fun missingOrPlaceholderRequiredTagsAreNotScrobbled() {
        for (comments in listOf(arrayOf("TITLE=Filename"), arrayOf("ARTIST=Artist"), arrayOf("TITLE=<unknown>", "ARTIST=Artist"), arrayOf("TITLE=Song", "ARTIST= "))) {
            assertNull(read(fixture(*comments)))
        }
    }
    @Test fun albumArtistIsAnOptionalCoverHintAndDoesNotReplaceTheTrackArtist() {
        val tags = read(fixture("TITLE=Song", "ARTIST=Singer", "ALBUM=Album", "album artist=Producer"))!!
        assertEquals("Singer", tags.artist)
        assertEquals("Producer", tags.albumArtist)
        val conflicting = read(fixture("TITLE=Song", "ARTIST=Singer", "ALBUMARTIST=A", "ALBUM ARTIST=B"))!!
        assertEquals("Singer", conflicting.artist)
        assertNull(conflicting.albumArtist)
    }
    @Test fun conflictingMultipleArtistsAreRejectedRatherThanGuessed() {
        assertNull(read(fixture("TITLE=Song", "ARTIST=A", "artist=B")))
        assertNotNull(read(fixture("TITLE=Song", "ARTIST=A", "artist=A")))
    }
    @Test fun truncatedAndOversizedCommentsFailWithoutAllocatingUnboundedData() {
        val bytes = fixture("TITLE=Song", "ARTIST=Artist")
        assertNull(read(bytes.copyOf(bytes.size - 1)))
        bytes[43] = 127 // Comment size > 1 MiB.
        assertNull(read(bytes))
    }
    @Test fun invalidUtf8AndControlCharactersAreRejected() {
        val bytes = fixture("TITLE=Song", "ARTIST=Artist")
        bytes[bytes.lastIndex] = 255.toByte()
        assertNull(read(bytes))
        assertNull(read(fixture("TITLE=Song\u0000", "ARTIST=Artist")))
    }
    @Test fun nonNativeAndTaglessFlacReturnNoMetadata() {
        assertNull(read("not-flac".toByteArray()))
        val bytes = fixture("TITLE=Song", "ARTIST=Artist")
        bytes[4] = 128.toByte() // Last STREAMINFO; following bytes must not be consulted.
        assertNull(read(bytes))
    }
    @Test fun retriesGrowExponentiallyAndStayWithinSixHours() {
        assertEquals(60_000L, scrobbleRetryDelay(0))
        assertEquals(120_000L, scrobbleRetryDelay(1))
        assertEquals(21_600_000L, scrobbleRetryDelay(Int.MAX_VALUE))
        assertEquals(60_000L, scrobbleRetryDelay(-1))
    }
}
