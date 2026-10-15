package com.kadaiyare.izuna.core.model

data class ListeningSession(
    val id: String,
    val track: Track,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val endedAtMs: Long? = null,
    val listenedMs: Long = 0,
) {
    val qualifiedForScrobble: Boolean get() = ScrobblePolicy.qualifies(track.durationMs, listenedMs)
}

/** Measures real playing time with a monotonic clock; never derives listening time from seeks. */
class ListeningTracker(private val newId: () -> String) {
    private var session: ListeningSession? = null
    private var wasPlaying = false
    private var lastElapsedMs: Long? = null

    fun update(track: Track?, playing: Boolean, elapsedMs: Long, wallMs: Long,
        boundary: Boolean = false, finished: Boolean = false): List<ListeningSession> {
        val changes = mutableListOf<ListeningSession>()
        val previous = session
        if (previous != null) {
            val delta = if (wasPlaying) (elapsedMs - (lastElapsedMs ?: elapsedMs)).coerceAtLeast(0) else 0
            val updated = previous.copy(listenedMs = previous.listenedMs + delta, updatedAtMs = wallMs.coerceAtLeast(previous.updatedAtMs))
            session = updated
            if (boundary || finished || track?.uri != previous.track.uri) {
                changes += updated.copy(endedAtMs = updated.updatedAtMs)
                session = null
            }
        }
        if (playing && !finished && track != null) {
            session = session?.copy(track = track) ?: ListeningSession(newId(), track, wallMs, wallMs)
        }
        session?.let(changes::add)
        wasPlaying = playing && !finished && session != null
        lastElapsedMs = elapsedMs
        return changes
    }
}
