package com.kadaiyare.izuna

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.data.library.MediaStoreLibrary
import com.kadaiyare.izuna.data.playback.PlaybackRepository
import com.kadaiyare.izuna.data.history.HistoryRepository
import com.kadaiyare.izuna.data.settings.SettingsRepository
import com.kadaiyare.izuna.playback.PlaybackService
import com.kadaiyare.izuna.playback.PlaybackConnection
import com.kadaiyare.izuna.playback.toMediaItem
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun localFlacPlaysAndAdvancesInBackgroundWithMediaNotification() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_AUDIO)
        val uris = mutableListOf<Uri>()
        var controller: MediaController? = null
        val connection = PlaybackConnection(context)
        val settings = SettingsRepository.get(context)
        val originalSettings = runBlocking { settings.data.first() }
        runBlocking { settings.setDiscordSharing(false); settings.setRestoreQueue(true); settings.setRecordHistory(true) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            repeat(2) { index ->
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "izuna-test-${System.nanoTime()}-$index.flac")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/IzunaTests")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
                uris += uri
                context.contentResolver.openOutputStream(uri)!!.use { output ->
                    instrumentation.context.assets.open("silence.flac").use { it.copyTo(output) }
                }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
            val all = runBlocking { MediaStoreLibrary(context.contentResolver).scan() }
            val ids = uris.map { it.lastPathSegment }.toSet()
            val tracks = all.filter { Uri.parse(it.uri).lastPathSegment in ids }
            assertEquals(2, tracks.size)
            // Some devices leave MediaStore duration unset for newly inserted FLACs.
            // A known value must be correct; validate the decoded length on Player below.
            assertTrue("Unexpected MediaStore durations: ${tracks.map { it.durationMs }}",
                tracks.all { it.durationMs == 0L || it.durationMs in 11_900..12_100 })
            lateinit var pending: ListenableFuture<MediaController>
            instrumentation.runOnMainSync {
                pending = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            }
            val player = pending.get(15, TimeUnit.SECONDS)
            controller = player
            instrumentation.runOnMainSync { connection.connect() }
            awaitCondition { connection.state.value.connected }
            instrumentation.runOnMainSync {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.setMediaItems(tracks.map { it.toMediaItem() })
                player.prepare()
                player.play()
            }
            awaitState(player) { isPlaying && currentPosition > 100 }
            awaitState(player) { duration in 11_900..12_100 }
            instrumentation.runOnMainSync { player.pause() }
            awaitState(player) { !isPlaying }
            val history = HistoryRepository.get(context)
            awaitCondition {
                runBlocking { history.observeRecent().first() }.any { it.track.uri == tracks.first().uri && it.listenedMs > 0 }
            }
            val firstSession = runBlocking { history.observeRecent().first() }.first { it.track.uri == tracks.first().uri }
            instrumentation.runOnMainSync { player.seekTo(2_000); player.play() }
            awaitState(player) { isPlaying && currentPosition >= 2_000 }
            instrumentation.runOnMainSync { player.pause() }
            awaitState(player) { !isPlaying }
            awaitCondition {
                runBlocking { history.observeRecent().first() }.any { it.id == firstSession.id && it.listenedMs > firstSession.listenedMs }
            }
            assertEquals(1, runBlocking { history.observeRecent().first() }.count { it.track.uri == tracks.first().uri })
            instrumentation.runOnMainSync { player.play() }
            awaitState(player) { isPlaying }
            instrumentation.runOnMainSync { player.repeatMode = Player.REPEAT_MODE_ONE; player.seekTo(11_700) }
            awaitState(player) { isPlaying && currentMediaItemIndex == 0 && currentPosition in 0..1_500 }
            instrumentation.runOnMainSync { player.repeatMode = Player.REPEAT_MODE_OFF; player.seekTo(2_000) }
            scenario.moveToState(Lifecycle.State.CREATED)
            awaitState(player) { isPlaying && currentPosition >= 2_500 }
            awaitCondition {
                context.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty()
            }
            instrumentation.runOnMainSync { player.seekTo(11_700) }
            awaitState(player) { currentMediaItemIndex == 1 && isPlaying }
            instrumentation.runOnMainSync { player.seekToPreviousMediaItem() }
            awaitState(player) { currentMediaItemIndex == 0 && isPlaying }
            instrumentation.runOnMainSync { player.repeatMode = Player.REPEAT_MODE_ALL; player.seekTo(1, 11_700) }
            awaitState(player) { currentMediaItemIndex == 0 && isPlaying }

            // The album action removes unrelated songs, preserves a paused position,
            // and wraps naturally from the album's last track back to its first.
            instrumentation.runOnMainSync {
                player.pause()
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.setMediaItems((tracks + tracks.first().copy(id = "unrelated")).map { it.toMediaItem() }, 1, 3_000)
                player.prepare()
            }
            awaitState(player) { playbackState == Player.STATE_READY && mediaItemCount == 3 && !isPlaying }
            instrumentation.runOnMainSync { connection.loopAlbum(tracks) }
            awaitState(player) {
                playbackState == Player.STATE_READY && mediaItemCount == 2 && currentMediaItemIndex == 1 &&
                    currentMediaItem?.mediaId == tracks[1].id && currentPosition == 3_000L &&
                    repeatMode == Player.REPEAT_MODE_ALL && !playWhenReady
            }
            instrumentation.runOnMainSync { player.seekTo(11_700); player.play() }
            awaitState(player) { currentMediaItemIndex == 0 && isPlaying && currentPosition < 1_500 }
            instrumentation.runOnMainSync {
                player.pause()
                player.seekTo(3_200)
                player.shuffleModeEnabled = true
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
            awaitState(player) { !isPlaying && shuffleModeEnabled && repeatMode == Player.REPEAT_MODE_ONE }
            awaitCondition {
                val saved = runBlocking { PlaybackRepository.get(context).load() }
                saved.currentIndex == 0 && saved.positionMs == 3_200L && saved.shuffle && saved.repeatMode == Player.REPEAT_MODE_ONE &&
                    saved.tracks.map { it.uri } == tracks.map { it.uri }
            }
            // Turning history off closes the existing record without stopping playback.
            runBlocking { settings.setRecordHistory(false) }
            awaitCondition {
                runBlocking { history.observeRecent().first() }.filter { it.track.uri in tracks.map { track -> track.uri } }
                    .all { it.endedAtMs != null }
            }
            val disabled = runBlocking { history.observeRecent().first() }
            instrumentation.runOnMainSync { player.play() }
            awaitState(player) { isPlaying && currentPosition >= 3_500 }
            instrumentation.runOnMainSync { player.pause() }
            awaitState(player) { !isPlaying }
            assertEquals(disabled, runBlocking { history.observeRecent().first() })
            runBlocking { settings.setRecordHistory(true) }
            instrumentation.runOnMainSync { player.play() }
            awaitState(player) { isPlaying && currentPosition >= 4_000 }
            instrumentation.runOnMainSync { player.pause() }
            awaitCondition {
                runBlocking { history.observeRecent().first() }.any { it.id !in disabled.map { row -> row.id } && it.listenedMs > 0 }
            }
            instrumentation.runOnMainSync { player.stop() }
            awaitState(player) { playbackState == Player.STATE_IDLE && !isPlaying }
            awaitCondition {
                val sessions = runBlocking { history.observeRecent().first() }.filter { it.track.uri in tracks.map { track -> track.uri } }
                sessions.size >= 3 && sessions.all { it.endedAtMs != null }
            }
            val sessions = runBlocking { history.observeRecent().first() }.filter { it.track.uri in tracks.map { track -> track.uri } }
            assertTrue(sessions.count { it.track.uri == tracks.first().uri } >= 2)
            assertTrue("12-second fixtures never qualify for Last.fm", sessions.none { it.qualifiedForScrobble })
            assertTrue("Seeking must not count skipped audio as listening", sessions.sumOf { it.listenedMs } < 20_000)

            // A deleted URI must surface a playback error instead of crashing the service.
            context.contentResolver.delete(Uri.parse(tracks.first().uri), null, null)
            // MediaStore rejects deleting an already removed row on newer Android versions.
            uris.removeAll { it.lastPathSegment == Uri.parse(tracks.first().uri).lastPathSegment }
            instrumentation.runOnMainSync {
                player.setMediaItem(tracks.first().toMediaItem())
                player.prepare()
            }
            awaitState(player) { playerError != null }
        } finally {
            instrumentation.runOnMainSync { connection.disconnect() }
            instrumentation.runOnMainSync { controller?.run {
                pause(); stop(); clearMediaItems(); shuffleModeEnabled = false; repeatMode = Player.REPEAT_MODE_OFF; release()
            } }
            scenario.close()
            runBlocking {
                settings.setRestoreQueue(originalSettings.restoreQueue)
                settings.setRecordHistory(originalSettings.recordHistory)
                settings.setDiscordSharing(originalSettings.discordSharing)
            }
            uris.forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun awaitState(player: MediaController, predicate: MediaController.() -> Boolean) = awaitCondition {
        var matches = false
        instrumentation.runOnMainSync { matches = player.predicate() }
        matches
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        throw AssertionError("Expected playback state was not reached within 15 seconds")
    }
}
