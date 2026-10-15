package com.kadaiyare.izuna.data.discord

enum class DiscordTitleFormat { SONG, SONG_ARTIST, ARTIST_SONG }
enum class DiscordCompactDisplay(val sdkValue: Int) { APP_NAME(0), TITLE(2) }

data class DiscordDisplaySettings(
    val showAlbum: Boolean = false,
    val titleFormat: DiscordTitleFormat = DiscordTitleFormat.SONG,
    val compactDisplay: DiscordCompactDisplay = DiscordCompactDisplay.APP_NAME,
) {
    fun format(title: String, artist: String, album: String?): DiscordDisplayText {
        val song = title.clean()
        val credit = artist.clean()
        val details = when (titleFormat) {
            DiscordTitleFormat.SONG -> song
            DiscordTitleFormat.SONG_ARTIST -> "$song · $credit"
            DiscordTitleFormat.ARTIST_SONG -> "$credit · $song"
        }.limited()
        val record = album?.clean()?.takeUnless { it.equals("<unknown>", ignoreCase = true) || it == "アルバム不明" }.orEmpty()
        val state = (if (showAlbum && record.isNotEmpty()) "$credit · $record" else credit).limited()
        return DiscordDisplayText(details, state, if (compactDisplay == DiscordCompactDisplay.APP_NAME) "Izuna" else details)
    }
}

data class DiscordDisplayText(val title: String, val subtitle: String, val compact: String)

// Keep metadata on one line and bound the final combined SDK fields without splitting surrogate pairs.
private fun String.clean() = replace(Regex("[\\r\\n\\t]+"), " ").trim()
private fun String.limited() = substring(0, offsetByCodePoints(0, minOf(128, codePointCount(0, length))))
