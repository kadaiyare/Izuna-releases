package com.kadaiyare.izuna.data.audio

import com.kadaiyare.izuna.core.model.FlacStreamInfo
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlacStreamInfoReaderTest {
    // Fixed STREAMINFO bytes, independent of reader bit arithmetic.
    private fun header(packed: String): ByteArray = hex("664c61438000002210001000000000000000${packed}0000000000000000000000000000000000000000")
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun readsCdQualityStereo() {
        val info = readFlacStreamInfo(ByteArrayInputStream(header("0ac442f0")))!!
        assertEquals(FlacStreamInfo(44_100, 16, 2), info)
        assertEquals("44.1 kHz", info.sampleRateLabel)
        assertEquals("ステレオ", info.channelsLabel)
        assertFalse(info.isHiRes)
    }

    @Test fun readsHighResolution24BitAndEightChannels() {
        val info = readFlacStreamInfo(ByteArrayInputStream(header("17700f70")))!!
        assertEquals(FlacStreamInfo(96_000, 24, 8), info)
        assertEquals("96 kHz", info.sampleRateLabel)
        assertEquals("8 ch", info.channelsLabel)
        assertTrue(info.isHiRes)
    }

    @Test fun reads32BitMono() {
        assertEquals(FlacStreamInfo(192_000, 32, 1), readFlacStreamInfo(ByteArrayInputStream(header("2ee001f0"))))
        assertEquals("モノラル", FlacStreamInfo(192_000, 32, 1).channelsLabel)
    }

    @Test fun rejectsWrongMagicMetadataTypeAndLength() {
        val valid = header("0ac442f0")
        for (index in listOf(0, 4, 7)) {
            val broken = valid.copyOf().apply { this[index] = 1 }
            assertNull(readFlacStreamInfo(ByteArrayInputStream(broken)))
        }
    }

    @Test fun rejectsTruncationAtEveryHeaderByte() {
        val valid = header("0ac442f0")
        for (size in 0 until 42) assertNull(readFlacStreamInfo(ByteArrayInputStream(valid.copyOf(size))))
    }

    @Test fun rejectsInvalidRateAndBitDepth() {
        assertNull(readFlacStreamInfo(ByteArrayInputStream(header("000002f0"))))
        assertNull(readFlacStreamInfo(ByteArrayInputStream(header("0ac44200"))))
    }

    @Test fun readsBeforeRemainingMetadataWithoutReadingAudio() {
        val bytes = header("0ac442f0").apply { this[4] = 0 } + ByteArray(1000) { 0x7f }
        val stream = ByteArrayInputStream(bytes)
        assertEquals(FlacStreamInfo(44_100, 16, 2), readFlacStreamInfo(stream))
        assertEquals(1000, stream.available())
    }

    @Test fun classifiesJeitaBoundaryCases() {
        for ((rate, depth) in listOf(44_100 to 24, 48_000 to 24, 96_000 to 16, 96_000 to 24)) {
            assertTrue("$rate / $depth", FlacStreamInfo(rate, depth, 2).isHiRes)
        }
        for ((rate, depth) in listOf(44_100 to 16, 48_000 to 16, 96_000 to 12, 32_000 to 24)) {
            assertFalse("$rate / $depth", FlacStreamInfo(rate, depth, 2).isHiRes)
        }
    }
}
