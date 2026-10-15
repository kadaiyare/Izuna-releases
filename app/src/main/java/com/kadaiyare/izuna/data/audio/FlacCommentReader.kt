package com.kadaiyare.izuna.data.audio

import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Bounded native FLAC/Vorbis comments only. No filename or MediaStore fallback. */
fun readFlacScrobbleTags(input: InputStream): ScrobbleMetadata? {
    try {
        val stream = DataInputStream(input)
        if (stream.readInt() != 0x664c6143) return null
        var total = 0L
        repeat(128) { index ->
            val header = stream.readUnsignedByte()
            val type = header and 127
            val size = (stream.readUnsignedByte() shl 16) or (stream.readUnsignedByte() shl 8) or stream.readUnsignedByte()
            total += size + 4
            if (total > 32L * 1024 * 1024 || (index == 0 && (type != 0 || size != 34))) return null
            if (type == 4) {
                if (size > 1024 * 1024) return null
                val bytes = ByteArray(size).also(stream::readFully)
                return parseComments(bytes)
            }
            stream.skipNBytes(size.toLong())
            if (header and 128 != 0) return null
        }
    } catch (_: IOException) {
        return null
    }
    return null
}

private fun parseComments(bytes: ByteArray): ScrobbleMetadata? = try {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun length(): Int {
        val value = buffer.int
        require(value >= 0 && value <= buffer.remaining())
        return value
    }
    val vendor = length()
    buffer.position(buffer.position() + vendor)
    val count = buffer.int
    require(count in 0..4096)
    val tags = mutableMapOf<String, String>()
    var albumArtistAmbiguous = false
    repeat(count) {
        val data = ByteArray(length()).also(buffer::get)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        val separator = text.indexOf('=')
        require(separator > 0)
        val name = text.substring(0, separator).uppercase(Locale.ROOT).let {
            if (it == "ALBUM ARTIST") "ALBUMARTIST" else it
        }
        if (name in setOf("TITLE", "ARTIST", "ALBUM", "ALBUMARTIST")) {
            val value = text.substring(separator + 1).trim()
            if (name == "ALBUMARTIST") {
                // This optional cover hint must not invalidate existing Last.fm / Discord tags.
                if (value.length > 4096 || value.any { it.isISOControl() } || (tags[name] != null && tags[name] != value))
                    albumArtistAmbiguous = true
                else tags[name] = value
                return@repeat
            }
            require(value.length <= 4096 && value.none { it.isISOControl() })
            // Multiple distinct artist/title tags are ambiguous; do not guess an attribution.
            require(tags[name] == null || tags[name] == value)
            tags[name] = value
        }
    }
    require(!buffer.hasRemaining())
    ScrobbleMetadata(tags["TITLE"] ?: "", tags["ARTIST"] ?: "", tags["ALBUM"]?.takeIf(String::isNotEmpty),
        tags["ALBUMARTIST"]?.takeIf { !albumArtistAmbiguous && it.isNotEmpty() })
        .takeIf(ScrobbleMetadata::valid)
} catch (_: Exception) { null }
