package com.kadaiyare.izuna.discordprobe

/** Only called by the probe's single native worker. UTF-8 preserves supplementary characters. */
internal class DiscordNative : ProbeTransport {
    private val native = com.kadaiyare.izuna.discordruntime.DiscordNative()
    override fun create(applicationId: Long) = native.create(applicationId)
    override fun publish(handle: Long, title: ByteArray, artist: ByteArray, listening: Boolean,
                         musicDetails: Boolean, startMs: Long, endMs: Long) = native.publish(handle, title, artist, listening, musicDetails, startMs, endMs)
    override fun clear(handle: Long) = native.clear(handle)
    override fun poll(handle: Long) = native.poll(handle)
    override fun version() = native.version()
    override fun destroy(handle: Long) = native.destroy(handle)

    companion object {
        fun load() { com.kadaiyare.izuna.discordruntime.DiscordNative.load(BuildConfig.DIAGNOSE_16KB) }
    }
}
