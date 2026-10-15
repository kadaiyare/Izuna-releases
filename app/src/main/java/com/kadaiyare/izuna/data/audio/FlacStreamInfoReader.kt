package com.kadaiyare.izuna.data.audio

import com.kadaiyare.izuna.core.model.FlacStreamInfo
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/** Reads only the mandatory native FLAC STREAMINFO header, never audio frames. */
fun readFlacStreamInfo(input: InputStream): FlacStreamInfo? {
    try {
        val stream = DataInputStream(input)
        if (stream.readInt() != 0x664c6143) return null // fLaC
        val type = stream.readUnsignedByte() and 0x7f
        val length = (stream.readUnsignedByte() shl 16) or (stream.readUnsignedByte() shl 8) or stream.readUnsignedByte()
        if (type != 0 || length != 34) return null
        val info = ByteArray(34)
        stream.readFully(info)
        fun byte(index: Int) = info[index].toInt() and 0xff
        val sampleRate = (byte(10) shl 12) or (byte(11) shl 4) or (byte(12) ushr 4)
        val channels = ((byte(12) ushr 1) and 7) + 1
        val depth = ((byte(12) and 1) shl 4) + (byte(13) ushr 4) + 1
        if (sampleRate == 0 || depth !in 4..32) return null
        return FlacStreamInfo(sampleRate, depth, channels)
    } catch (_: IOException) {
        return null
    }
}
