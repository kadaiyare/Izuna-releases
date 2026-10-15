package com.kadaiyare.izuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.data.settings.AppSettings
import com.kadaiyare.izuna.data.settings.SettingsRepository
import com.kadaiyare.izuna.data.discord.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun newFile() = File(context.cacheDir, "settings-test-${System.nanoTime()}.preferences_pb")

    @Test fun defaultsAndSavedChoicesSurviveReopening() = runBlocking {
        val file = newFile()
        var job = SupervisorJob()
        try {
            val first = SettingsRepository.create(file, CoroutineScope(job + Dispatchers.IO))
            assertEquals(AppSettings(), first.data.first())
            assertFalse(first.data.first().recordHistory)
            assertFalse(first.data.first().onlineLyrics)
            assertFalse(first.data.first().discordSharing)
            first.setRestoreQueue(false)
            first.setRecordHistory(true)
            first.setDiscordSharing(true)
            first.setDiscordCoverLookup(true)
            first.setOnlineLyrics(true)
            first.setGroupArtistsByAlbumArtist(true)
            first.setDiscordShowAlbum(true)
            first.setDiscordTitleFormat(DiscordTitleFormat.ARTIST_SONG)
            first.setDiscordCompactDisplay(DiscordCompactDisplay.TITLE)
            job.cancelAndJoin()
            job = SupervisorJob()
            val reopened = SettingsRepository.create(file, CoroutineScope(job + Dispatchers.IO))
            assertEquals(AppSettings(restoreQueue = false, recordHistory = true, discordSharing = true, discordCoverLookup = true,
                onlineLyrics = true, groupArtistsByAlbumArtist = true,
                discordDisplay = DiscordDisplaySettings(true, DiscordTitleFormat.ARTIST_SONG, DiscordCompactDisplay.TITLE)), reopened.data.first())
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun concurrentEditsDoNotOverwriteTheOtherSetting() = runBlocking {
        val file = newFile()
        val job = SupervisorJob()
        try {
            val settings = SettingsRepository.create(file, CoroutineScope(job + Dispatchers.IO))
            coroutineScope {
                launch { settings.setRestoreQueue(false) }
                launch { settings.setRecordHistory(true) }
            }
            assertEquals(AppSettings(false, true), settings.data.first())
            settings.setRestoreQueue(true)
            assertEquals(AppSettings(true, true), settings.data.first())
        } finally { job.cancelAndJoin(); file.delete() }
    }
    @Test fun unknownDisplayChoicesFallBackWithoutEnablingSharing() = runBlocking {
        val file = newFile()
        var job = SupervisorJob()
        try {
            val raw = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
            raw.edit {
                it[stringPreferencesKey("discord_title_format")] = "FUTURE_FORMAT"
                it[stringPreferencesKey("discord_compact_display")] = "FUTURE_DISPLAY"
            }
            job.cancelAndJoin()
            job = SupervisorJob()
            val settings = SettingsRepository.create(file, CoroutineScope(job + Dispatchers.IO))
            assertEquals(AppSettings(), settings.data.first())
            coroutineScope {
                launch { settings.setDiscordShowAlbum(true) }
                launch { settings.setDiscordTitleFormat(DiscordTitleFormat.SONG_ARTIST) }
                launch { settings.setDiscordCompactDisplay(DiscordCompactDisplay.TITLE) }
            }
            assertEquals(DiscordDisplaySettings(true, DiscordTitleFormat.SONG_ARTIST, DiscordCompactDisplay.TITLE), settings.data.first().discordDisplay)
            org.junit.Assert.assertFalse(settings.data.first().discordSharing)
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun corruptPreferencesDisableRecordingAndRestoreInsteadOfEnablingThem() = runBlocking {
        val file = newFile()
        val job = SupervisorJob()
        try {
            file.writeBytes(byteArrayOf(0x0a, 0x7f, 0x01))
            val settings = SettingsRepository.create(file, CoroutineScope(job + Dispatchers.IO))
            assertEquals(AppSettings(false, false), settings.data.first())
            settings.setRecordHistory(true)
            assertEquals(AppSettings(false, true), settings.data.first())
        } finally { job.cancelAndJoin(); file.delete() }
    }
}
