package com.kadaiyare.izuna.discordprobe

/** Immutable, fictional playback clock. Only explicit commands change the anchor or options. */
internal data class DemoPlayback(
    val secondTrack: Boolean,
    val listening: Boolean,
    val musicDetails: Boolean,
    val anchorElapsedMs: Long,
    val positionMs: Long = 45_000,
    val isPlaying: Boolean = true,
) {
    fun positionAt(elapsedMs: Long): Long {
        if (!isPlaying) return positionMs
        val delta = elapsedMs.coerceAtLeast(anchorElapsedMs) - anchorElapsedMs
        val remaining = DURATION_MS - positionMs
        return if (delta >= remaining) DURATION_MS else positionMs + delta
    }

    fun pause(elapsedMs: Long) = copy(positionMs = positionAt(elapsedMs), anchorElapsedMs = elapsedMs, isPlaying = false)
    fun resume(elapsedMs: Long) = if (isPlaying) this else copy(anchorElapsedMs = elapsedMs, isPlaying = true)
    fun seek(positionMs: Long, elapsedMs: Long) = copy(positionMs = positionMs.coerceIn(0, DURATION_MS), anchorElapsedMs = elapsedMs)

    companion object { const val DURATION_MS = 180_000L }
}
