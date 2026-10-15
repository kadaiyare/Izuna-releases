package com.kadaiyare.izuna.core.model

import java.text.Normalizer
import java.util.Locale

data class LyricsQuery(val title: String, val artist: String, val album: String?, val durationSeconds: Int) {
    fun valid() = title.isNotBlank() && artist.isNotBlank() && durationSeconds in 1..3600 &&
        listOf(title, artist, album.orEmpty()).all { it.length <= 512 && it.none(Char::isISOControl) }
}

data class LyricsDocument(val source: String, val recordId: Long, val plain: String?, val synced: String?,
    val instrumental: Boolean = false)

data class LyricLine(val timeMs: Long, val text: String)

fun normalizedLyricsTag(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

/** Line-level LRC, including repeated timestamps and a global offset. Bounded before parsing. */
fun parseLrc(text: String?): List<LyricLine> {
    if (text == null || text.length > 131072) return emptyList()
    val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val offset = Regex("(?im)^\\[offset:([+-]?\\d+)]\\s*$").find(text)?.groupValues?.get(1)
        ?.toLongOrNull()?.coerceIn(-60_000, 60_000) ?: 0L
    val result = mutableListOf<LyricLine>()
    for (raw in text.lineSequence().take(5000)) {
        val row = raw.trim().removePrefix("\uFEFF")
        val times = mutableListOf<Long>()
        var end = 0
        while (true) {
            val match = stamp.find(row, end)?.takeIf { it.range.first == end } ?: break
            val seconds = match.groupValues[2].toInt()
            if (seconds >= 60) break
            val fraction = match.groupValues[3].padEnd(3, '0').ifEmpty { "000" }.toInt()
            times += (match.groupValues[1].toLong() * 60_000 + seconds * 1000 + fraction - offset).coerceAtLeast(0)
            end = match.range.last + 1
        }
        val lyric = row.substring(end).trim()
        times.forEach { result += LyricLine(it, lyric) }
        if (result.size > 5000) return emptyList()
    }
    return result.sortedBy { it.timeMs }
}

/** Positive user adjustment delays lyrics. Position always comes from the real Player. */
fun activeLyricIndex(lines: List<LyricLine>, positionMs: Long, adjustmentMs: Long = 0): Int {
    val lyricPosition = positionMs - adjustmentMs.coerceIn(-10_000, 10_000)
    var low = 0
    var high = lines.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (lines[middle].timeMs <= lyricPosition) low = middle + 1 else high = middle
    }
    return low - 1
}
