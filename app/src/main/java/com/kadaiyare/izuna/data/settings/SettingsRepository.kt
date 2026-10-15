package com.kadaiyare.izuna.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kadaiyare.izuna.data.discord.DiscordDisplaySettings
import com.kadaiyare.izuna.data.discord.DiscordTitleFormat
import com.kadaiyare.izuna.data.discord.DiscordCompactDisplay
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map

data class AppSettings(val restoreQueue: Boolean = true, val recordHistory: Boolean = false,
                       val discordSharing: Boolean = false, val discordCoverLookup: Boolean = false,
                       val onlineLyrics: Boolean = false, val groupArtistsByAlbumArtist: Boolean = false,
                       val discordDisplay: DiscordDisplaySettings = DiscordDisplaySettings())

/** A single settings store shared by the UI and service; successful edits are durable. */
class SettingsRepository private constructor(private val store: DataStore<Preferences>) {
    val data = store.data.map { preferences ->
        AppSettings(preferences[restoreQueue] ?: true, preferences[recordHistory] ?: false,
            preferences[discordSharing] ?: false, preferences[discordCoverLookup] ?: false,
            preferences[onlineLyrics] ?: false, preferences[groupArtistsByAlbumArtist] ?: false,
            DiscordDisplaySettings(preferences[discordShowAlbum] ?: false,
                DiscordTitleFormat.entries.firstOrNull { it.name == preferences[discordTitleFormat] } ?: DiscordTitleFormat.SONG,
                DiscordCompactDisplay.entries.firstOrNull { it.name == preferences[discordCompactDisplay] } ?: DiscordCompactDisplay.APP_NAME))
    }

    suspend fun setRestoreQueue(enabled: Boolean) { store.edit { it[restoreQueue] = enabled } }
    suspend fun setRecordHistory(enabled: Boolean) { store.edit { it[recordHistory] = enabled } }
    suspend fun setDiscordSharing(enabled: Boolean) { store.edit { it[discordSharing] = enabled } }
    suspend fun setDiscordCoverLookup(enabled: Boolean) { store.edit { it[discordCoverLookup] = enabled } }
    suspend fun setOnlineLyrics(enabled: Boolean) { store.edit { it[onlineLyrics] = enabled } }
    suspend fun setGroupArtistsByAlbumArtist(enabled: Boolean) { store.edit { it[groupArtistsByAlbumArtist] = enabled } }
    suspend fun setDiscordShowAlbum(enabled: Boolean) { store.edit { it[discordShowAlbum] = enabled } }
    suspend fun setDiscordTitleFormat(format: DiscordTitleFormat) { store.edit { it[discordTitleFormat] = format.name } }
    suspend fun setDiscordCompactDisplay(display: DiscordCompactDisplay) { store.edit { it[discordCompactDisplay] = display.name } }

    companion object {
        private val restoreQueue = booleanPreferencesKey("restore_queue")
        private val recordHistory = booleanPreferencesKey("record_history")
        private val discordSharing = booleanPreferencesKey("discord_sharing")
        private val discordCoverLookup = booleanPreferencesKey("discord_cover_lookup")
        private val discordShowAlbum = booleanPreferencesKey("discord_show_album")
        private val discordTitleFormat = stringPreferencesKey("discord_title_format")
        private val discordCompactDisplay = stringPreferencesKey("discord_compact_display")
        private val onlineLyrics = booleanPreferencesKey("online_lyrics")
        private val groupArtistsByAlbumArtist = booleanPreferencesKey("group_artists_by_album_artist")
        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext.noBackupFilesDir.resolve("settings.preferences_pb"),
                CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { instance = it }
        }

        internal fun create(file: File, scope: CoroutineScope) = SettingsRepository(PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler {
                Log.w("IzunaSettings", "Corrupt settings; history recording and queue restoration disabled")
                preferencesOf(restoreQueue to false, recordHistory to false)
            },
            scope = scope,
            produceFile = { file },
        ))
    }
}
