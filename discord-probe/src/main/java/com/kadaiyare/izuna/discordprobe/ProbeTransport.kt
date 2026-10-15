package com.kadaiyare.izuna.discordprobe

/** A fake transport can exercise races and errors without publishing to a Discord account. */
internal interface ProbeTransport {
    fun create(applicationId: Long): Long
    fun publish(handle: Long, title: ByteArray, artist: ByteArray, listening: Boolean,
                musicDetails: Boolean = false, startMs: Long = 0, endMs: Long = 0)
    fun clear(handle: Long)
    fun poll(handle: Long): IntArray
    fun version(): String
    fun destroy(handle: Long)
}
