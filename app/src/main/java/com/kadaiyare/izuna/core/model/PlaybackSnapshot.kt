package com.kadaiyare.izuna.core.model

/** A restart checkpoint, not a second live playback state. Never stores playWhenReady. */
data class PlaybackSnapshot(
    val tracks: List<Track> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)

/** Keep queue order and duplicates, selecting the next survivor if the selected file disappeared. */
fun PlaybackSnapshot.withAvailableTracks(available: Map<String, Track>): PlaybackSnapshot {
    val remaining = tracks.mapIndexedNotNull { index, track -> available[track.id]?.let { index to it } }
    if (remaining.isEmpty()) return copy(tracks = emptyList(), currentIndex = 0, positionMs = 0)
    val index = remaining.indexOfFirst { it.first >= currentIndex }.takeIf { it >= 0 } ?: remaining.lastIndex
    val selected = remaining[index]
    val position = if (selected.first == currentIndex) positionMs.coerceAtLeast(0).let {
        if (selected.second.durationMs > 0) it.coerceAtMost(selected.second.durationMs) else it
    } else 0
    return copy(tracks = remaining.map { it.second }, currentIndex = index, positionMs = position)
}
