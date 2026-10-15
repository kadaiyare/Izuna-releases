package com.kadaiyare.izuna.core.model

/** Last.fm's time requirement only; account authorization and metadata are separate checks. */
object ScrobblePolicy {
    fun requiredListeningMs(durationMs: Long): Long? {
        if (durationMs <= 30_000) return null
        // Round an odd millisecond up, and avoid overflow for unexpectedly large durations.
        return minOf(durationMs / 2 + durationMs % 2, 240_000)
    }

    fun qualifies(durationMs: Long, listenedMs: Long): Boolean =
        requiredListeningMs(durationMs)?.let { listenedMs >= it } ?: false
}

/** Supplied after authentication and explicit enablement; never created from local history alone. */
data class ScrobbleDestination(val accountName: String, val enabledAtMs: Long) {
    init { require(accountName.isNotBlank() && enabledAtMs > 0) }
}

/** Must come from structured audio tags. Library display names and filenames are not a source. */
data class ScrobbleMetadata(val title: String, val artist: String, val album: String? = null,
    val albumArtist: String? = null) {
    fun valid(): Boolean = title.isNotBlank() && artist.isNotBlank() &&
        !title.trim().equals("<unknown>", ignoreCase = true) && title.trim() != "タイトル不明" &&
        !artist.trim().equals("<unknown>", ignoreCase = true) && artist.trim() != "アーティスト不明"
}
