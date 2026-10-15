package com.kadaiyare.izuna

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.kadaiyare.izuna.core.model.*
import com.kadaiyare.izuna.data.lyrics.*
import com.kadaiyare.izuna.data.settings.SettingsRepository
import com.kadaiyare.izuna.playback.*
import com.kadaiyare.izuna.ui.player.LyricsViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real silent FLAC playback, original lyric fixtures, no network or external sharing. */
class LyricsPlaybackUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativeTagsCachedLyricsFollowPlaybackSeekTrackChangeAndForegroundReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = compose.activity
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_AUDIO)
        val settings = SettingsRepository.get(context)
        val original = runBlocking { settings.data.first() }
        val uris = mutableListOf<Uri>()
        val cacheFiles = mutableListOf<File>()
        val root = context.cacheDir.resolve("lyrics-playback-${System.nanoTime()}").apply { mkdirs() }
        val job = SupervisorJob()
        var player: MediaController? = null
        var saved = emptyList<MediaItem>()
        var savedIndex = 0
        var savedPosition = 0L
        var savedShuffle = false
        var savedRepeat = Player.REPEAT_MODE_OFF
        var captured = false
        try {
            runBlocking { settings.setDiscordSharing(false); settings.setRecordHistory(false); settings.setOnlineLyrics(false) }
            val repository = LyricsRepository.create(root, CoroutineScope(job + Dispatchers.IO), LyricsProvider { query ->
                val label = if (query.title.endsWith("B")) "Second" else "Original"
                LyricsDocument("LRCLIB", 1, null, (0..11).joinToString("\n") { "[00:%02d]$label test line $it".format(it) })
            })
            val titles = listOf("Izuna Lyrics ${System.nanoTime()} A", "Izuna Lyrics ${System.nanoTime()} B")
            titles.forEach { title ->
                val query = LyricsQuery(title, "Izuna Tests", "Lyrics Test Album", 12)
                runBlocking { repository.load(query, online = true) }
                val cached = context.noBackupFilesDir.resolve("lyrics/${LyricsRepository.key(query)}.json")
                cached.parentFile!!.mkdirs()
                root.resolve("lyrics/${cached.name}").copyTo(cached)
                cacheFiles += cached
                val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "lyrics-${System.nanoTime()}.flac")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/IzunaTests")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }))
                uris += uri
                context.contentResolver.openOutputStream(uri)!!.use {
                    val source = instrumentation.context.assets.open("silence.flac").use { input -> input.readBytes() }
                    it.write(withTags(source, title))
                }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
            lateinit var pending: ListenableFuture<MediaController>
            compose.runOnUiThread { pending = MediaController.Builder(context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            val controller = pending.get(15, TimeUnit.SECONDS)
            player = controller
            compose.waitUntil(15_000) { onPlayer(controller) {
                sessionExtras.getBoolean(PLAYBACK_READY) && mediaItemCount == sessionExtras.getInt(PLAYBACK_QUEUE_SIZE, -1)
            } }
            compose.runOnUiThread {
                saved = (0 until controller.mediaItemCount).map(controller::getMediaItemAt)
                savedIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
                savedPosition = controller.currentPosition
                savedShuffle = controller.shuffleModeEnabled; savedRepeat = controller.repeatMode; captured = true
                controller.pause(); controller.shuffleModeEnabled = false; controller.repeatMode = Player.REPEAT_MODE_OFF
                controller.setMediaItems(uris.mapIndexed { index, uri ->
                    Track(uri.toString(), uri.toString(), titles[index], "Izuna Tests", "Lyrics Test Album", 12_000, null, 0, 0).toMediaItem()
                })
                controller.prepare()
            }
            compose.waitUntil(15_000) { onPlayer(controller) { playbackState == Player.STATE_READY && duration in 11_900..12_100 } }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("miniPlayer").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("miniPlayer").performClick()
            val lyrics = ViewModelProvider(context)[LyricsViewModel::class.java]
            // Composing an adjacent page and cancelling a short swipe must not activate lyrics.
            compose.runOnIdle { assertFalse(lyrics.isVisible) }
            compose.onNodeWithTag("playerPager").performTouchInput {
                swipe(Offset(centerX, centerY), Offset(centerX - width * 0.06f, centerY), 800)
            }
            compose.onNodeWithContentDescription("再生キュー").assertIsDisplayed()
            compose.runOnIdle { assertFalse(lyrics.isVisible) }
            compose.onNodeWithTag("playerPager").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("lyricsScreen").assertIsDisplayed()
            waitLine("Original test line 0")
            compose.runOnIdle { assertTrue(lyrics.isVisible) }
            compose.onNodeWithText("LRCLIB · 同期歌詞 · 保存済み").assertIsDisplayed()
            // Vertical lyric gestures scroll the lyrics and leave horizontal navigation alone.
            compose.onNodeWithTag("syncedLyrics").performTouchInput { swipeUp() }
            compose.onNodeWithTag("lyricsScreen").assertIsDisplayed()
            compose.onNodeWithTag("playerSheet").assertIsDisplayed()
            compose.onNodeWithTag("resumeLyricsFollow").performClick()
            compose.onNodeWithTag("playerPager").performTouchInput { swipeRight() }
            compose.onNodeWithContentDescription("再生キュー").assertIsDisplayed()
            compose.runOnIdle { assertFalse(lyrics.isVisible) }
            // The existing button enters the same adjoining page with animation.
            compose.onNodeWithTag("openLyrics").performClick()
            waitLine("Original test line 0")
            compose.runOnUiThread { controller.seekTo(5000) }
            waitLine("Original test line 5")
            compose.runOnUiThread { controller.play() }
            compose.waitUntil(10_000) { onPlayer(controller) { isPlaying && currentPosition >= 6100 } }
            waitLine("Original test line 6")
            compose.runOnUiThread { controller.pause(); controller.seekTo(2000) }
            waitLine("Original test line 2")
            Thread.sleep(1200)
            compose.onNodeWithTag("lyricLine-2").assertIsSelected().assertIsDisplayed()
            // A background/foreground round trip must restart the visible lyrics session.
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.waitForIdleSync()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitLine("Original test line 2")
            compose.runOnUiThread { controller.seekTo(7000) }
            waitLine("Original test line 7")
            // Compose idle does not wait for Samsung's foreground window transition.
            // Capture the settled window rather than a frame of the task-opening animation.
            Thread.sleep(1000)
            // Save only original test lyrics, never lyrics downloaded from the live service.
            instrumentation.uiAutomation.takeScreenshot().let { screenshot ->
                File(context.getExternalFilesDir(null), "lyrics-test.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            compose.runOnUiThread { controller.seekTo(1, 3000) }
            waitLine("Second test line 3")
            compose.onNodeWithText("Original test line 7").assertDoesNotExist()
            compose.runOnUiThread { context.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("lyricsScreen").assertIsNotDisplayed()
            compose.onNodeWithContentDescription("再生キュー").assertIsDisplayed()
            compose.runOnIdle { assertFalse(lyrics.isVisible) }
            assertFalse(runBlocking { settings.data.first().onlineLyrics })
            compose.runOnUiThread { context.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("playerSheet").fetchSemanticsNodes().isEmpty() }
            assertTrue(onPlayer(controller) { !isPlaying && currentMediaItemIndex == 1 && currentPosition == 3000L })
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnUiThread { player?.run {
                pause()
                if (captured) { setMediaItems(saved, savedIndex, savedPosition); shuffleModeEnabled = savedShuffle; repeatMode = savedRepeat }
                release()
            } }
            runBlocking {
                settings.setOnlineLyrics(original.onlineLyrics); settings.setRecordHistory(original.recordHistory)
                settings.setDiscordSharing(original.discordSharing)
                job.cancelAndJoin()
            }
            uris.forEach { context.contentResolver.delete(it, null, null) }
            cacheFiles.forEach { it.delete() }
            root.deleteRecursively()
        }
    }

    private fun waitLine(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(text) and isSelected()).assertIsDisplayed()
    }
    private fun onPlayer(player: MediaController, condition: MediaController.() -> Boolean): Boolean {
        var result = false
        compose.runOnUiThread { result = player.condition() }
        return result
    }

    /** Retain STREAMINFO and encoded silence; replace comments with unique, original test tags. */
    private fun withTags(source: ByteArray, title: String): ByteArray {
        var position = 4
        var streamInfo: ByteArray? = null
        while (true) {
            val type = source[position].toInt() and 127
            val last = source[position].toInt() and 128 != 0
            val length = ((source[position + 1].toInt() and 255) shl 16) or
                ((source[position + 2].toInt() and 255) shl 8) or (source[position + 3].toInt() and 255)
            if (type == 0) streamInfo = source.copyOfRange(position + 4, position + 4 + length)
            position += 4 + length
            if (last) break
        }
        val comments = ByteArrayOutputStream()
        fun little(value: Int) { comments.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        little(0); little(3)
        listOf("TITLE=$title", "ARTIST=Izuna Tests", "ALBUM=Lyrics Test Album").forEach {
            val bytes = it.toByteArray(Charsets.UTF_8); little(bytes.size); comments.write(bytes)
        }
        val result = ByteArrayOutputStream()
        result.write("fLaC".toByteArray())
        fun block(type: Int, bytes: ByteArray) {
            result.write(type); result.write(bytes.size shr 16); result.write(bytes.size shr 8); result.write(bytes.size); result.write(bytes)
        }
        block(0, requireNotNull(streamInfo)); block(132, comments.toByteArray())
        result.write(source, position, source.size - position)
        return result.toByteArray()
    }
}
