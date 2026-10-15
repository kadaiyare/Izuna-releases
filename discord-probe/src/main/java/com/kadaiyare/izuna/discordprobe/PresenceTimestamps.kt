package com.kadaiyare.izuna.discordprobe

/** SDK 1.10 uses Unix milliseconds. Discord advances the timer without periodic RPC updates. */
internal data class PresenceTimestamps(val startMs: Long, val endMs: Long) {
    companion object {
        fun fromPlayback(nowMs: Long, positionMs: Long, durationMs: Long): PresenceTimestamps? {
            if (durationMs <= 0 || positionMs < 0 || positionMs >= durationMs || nowMs <= positionMs) return null
            val remainingMs = durationMs - positionMs
            if (nowMs > Long.MAX_VALUE - remainingMs) return null
            return PresenceTimestamps(nowMs - positionMs, nowMs + remainingMs)
        }
    }
}
