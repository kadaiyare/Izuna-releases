package com.kadaiyare.izuna

import android.content.ComponentName
import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.kadaiyare.izuna.data.playlist.PlaylistRepository
import com.kadaiyare.izuna.data.settings.SettingsRepository
import com.kadaiyare.izuna.playback.PlaybackConnection
import com.kadaiyare.izuna.playback.PlaybackService
import com.kadaiyare.izuna.playback.PLAYBACK_READY
import com.kadaiyare.izuna.playback.PLAYBACK_QUEUE_SIZE
import com.kadaiyare.izuna.ui.library.LibraryViewModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*

class NavigationUiTest {
    @get:Rule(order = 0) val audioPermission = AudioPermissionRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var connection: PlaybackConnection
    private var originalSharing = false

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsRepository.get(context)
        originalSharing = runBlocking { settings.data.first().discordSharing }
        runBlocking { settings.setDiscordSharing(false) }
        connection = PlaybackConnection(context)
        compose.runOnUiThread { connection.connect() }
        compose.waitUntil(15_000) { connection.state.value.connected }
        // A connected service does not imply that the activity has composed its first frame.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("libraryTab-0")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    @After fun tearDown() {
        compose.runOnUiThread { connection.disconnect() }
        runBlocking { SettingsRepository.get(compose.activity).setDiscordSharing(originalSharing) }
    }

    @Test fun libraryCategoriesCanBeSwipedBothWaysAndTappedAcrossPages() {
        compose.onNodeWithTag("libraryTab-1").assertIsSelected()
        listOf("曲", "アルバム", "アーティスト", "プレイリスト").forEachIndexed { index, label ->
            compose.onNodeWithTag("libraryTab-$index").assertTextEquals(label)
        }
        captureLibraryIfRequested("albums")
        compose.onNodeWithTag("libraryTab-0").performClick().assertIsSelected()
        repeat(3) { index ->
            compose.onNodeWithTag("libraryPager").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("libraryTab-${index + 1}").assertIsSelected()
            if (index < 2) captureLibraryIfRequested(if (index == 0) "albums" else "artists")
        }
        compose.onNodeWithTag("libraryPager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("libraryTab-2").assertIsSelected()
        compose.onNodeWithTag("libraryTab-0").performClick().assertIsSelected()
        compose.onNodeWithTag("libraryTab-3").performClick().assertIsSelected()
        compose.onNodeWithText("新規作成").assertIsDisplayed()
    }

    private fun captureLibraryIfRequested(category: String) {
        if (InstrumentationRegistry.getArguments().getString("captureLibraryScreens") != "true") return
        compose.waitForIdle()
        Thread.sleep(1000)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { screenshot ->
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "library-$category.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }

    @Test fun playlistsCanBeEditedInsideTheLibrary() {
        val repository = PlaylistRepository.get(compose.activity)
        val name = "UI Test ${System.nanoTime()}"
        val renamed = "$name renamed"
        try {
            compose.onNodeWithTag("libraryTab-3").performClick()
            compose.onNodeWithText("新規作成").performClick()
            compose.onNodeWithText("保存").assertIsNotEnabled()
            compose.onNode(hasSetTextAction()).performTextInput(name)
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(name).performClick()
            compose.onNodeWithText("名前を変更").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement(renamed)
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(renamed).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("削除").performClick()
            compose.onAllNodesWithText("削除").onLast().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(renamed).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("新規作成").assertIsDisplayed()
        } finally {
            runBlocking { repository.data.first().filter { it.name == name || it.name == renamed }.forEach { repository.delete(it.id) } }
        }
    }

    @Test fun compactLibraryHeaderScrollsAwayAndTabsStayAvailable() {
        compose.onNodeWithTag("libraryTab-0").performClick()
        val header = compose.onNodeWithTag("libraryExpandedHeader")
        val initialHeight = header.fetchSemanticsNode().boundsInRoot.height
        val toolbarTop = compose.onNodeWithTag("libraryToolbar").fetchSemanticsNode().boundsInRoot.top
        Assert.assertTrue(initialHeight > 0f)
        compose.onNodeWithTag("libraryPager").performTouchInput { swipeUp() }
        Assert.assertTrue("The expanded header must collapse", header.fetchSemanticsNode().boundsInRoot.height < initialHeight / 2)
        Assert.assertTrue(compose.onNodeWithTag("libraryToolbar").fetchSemanticsNode().boundsInRoot.top < toolbarTop)
        compose.onNodeWithTag("libraryTab-0").assertIsDisplayed()
        compose.onNodeWithTag("libraryPager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("libraryTab-1").assertIsSelected().assertIsDisplayed()
        Assert.assertTrue("Changing category must preserve the collapsed header", header.fetchSemanticsNode().boundsInRoot.height < initialHeight / 2)
        compose.onNodeWithTag("libraryPager").performTouchInput { swipeDown() }
        Assert.assertTrue("Returning toward the top must expand the header", header.fetchSemanticsNode().boundsInRoot.height > initialHeight / 2)
    }

    @Test fun playerQueueHistoryAndDismissReturnToEachLibraryCategory() {
        val context = compose.activity
        lateinit var pending: ListenableFuture<MediaController>
        compose.runOnUiThread { pending = MediaController.Builder(context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val player = pending.get(15, TimeUnit.SECONDS)
        var saved = emptyList<MediaItem>()
        var savedIndex = 0
        var savedPosition = 0L
        var savedShuffle = false
        var savedRepeat = Player.REPEAT_MODE_OFF
        // Preserve the restored queue, rather than snapshotting the service before restoration finishes.
        compose.waitUntil(15_000) {
            var ready = false
            compose.runOnUiThread {
                ready = player.sessionExtras.getBoolean(PLAYBACK_READY) &&
                    player.mediaItemCount == player.sessionExtras.getInt(PLAYBACK_QUEUE_SIZE, -1)
            }
            ready
        }
        compose.runOnUiThread {
            saved = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            savedIndex = player.currentMediaItemIndex.coerceAtLeast(0)
            savedPosition = player.currentPosition
            savedShuffle = player.shuffleModeEnabled
            savedRepeat = player.repeatMode
            player.pause()
            player.shuffleModeEnabled = false
            player.repeatMode = Player.REPEAT_MODE_OFF
            // Never prepare/play this item: the test only exercises navigation and gestures.
            val items = listOf("Earlier Navigation", "Navigation Test", "Upcoming Navigation").mapIndexed { index, title ->
                MediaItem.Builder().setMediaId("navigation-test-$index").setUri("asset:///silence.flac")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Izuna Tests").build()).build()
            }
            player.setMediaItems(items, 1, 0)
        }
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("miniPlayer").fetchSemanticsNodes().isNotEmpty() }
            val library = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]
            compose.waitUntil(15_000) { !library.state.value.scanning }
            library.state.value.tracks.lastOrNull()?.let { last ->
                compose.onNodeWithTag("libraryTab-0").performClick()
                val list = compose.onNode(hasScrollToIndexAction() and !hasTestTag("libraryPager"))
                val lastRow = hasText(last.title) and hasText(last.artist) and hasClickAction()
                list.performScrollToNode(lastRow).performTouchInput { swipeUp() }
                val rowBottom = compose.onNode(lastRow).fetchSemanticsNode().boundsInRoot.bottom
                val miniTop = compose.onNodeWithTag("miniPlayer").fetchSemanticsNode().boundsInRoot.top
                Assert.assertTrue("The final song must scroll clear of the floating player", rowBottom <= miniTop)
                list.performScrollToIndex(0)
            }
            repeat(4) { tab ->
                compose.onNodeWithTag("libraryTab-$tab").performClick()
                openMiniPlayer()
                compose.onNodeWithTag("playerSheet").assertIsDisplayed()
                compose.onNodeWithContentDescription("再生キュー").assertIsDisplayed()
                // Sound processing is paused and remains on the archived feature branch.
                compose.onNodeWithText("音質と出力").assertDoesNotExist()
                compose.onNodeWithTag("soundPanel").assertDoesNotExist()
                // A finger moving right must reveal the adjoining queue, while staying in the sheet.
                compose.onNodeWithTag("playerPager").performTouchInput { swipeRight() }
                compose.onNodeWithText("キューと履歴").assertIsDisplayed()
                compose.onNodeWithTag("currentQueueItem").assertIsSelected().assertIsDisplayed()
                compose.onNodeWithTag("queue-2").assertIsDisplayed().assertTextContains("Upcoming Navigation")
                compose.onNodeWithText("Earlier Navigation").assertDoesNotExist()
                // A vertical swipe within the queue/history list must scroll instead of dismissing it.
                compose.onNodeWithTag("queueHistoryList").performTouchInput { swipeDown() }
                compose.onNodeWithTag("playerSheet").assertIsDisplayed()
                compose.onNodeWithTag("playerPager").performTouchInput { swipeLeft() }
                compose.onNodeWithContentDescription("再生キュー").assertIsDisplayed()
                // The opposite direction opens lyrics, with a reverse swipe back to the player.
                compose.onNodeWithTag("playerPager").performTouchInput { swipeLeft() }
                compose.onNodeWithTag("lyricsScreen").assertIsDisplayed()
                compose.onNodeWithTag("playerSheet").assertIsDisplayed()
                compose.onNodeWithTag("playerPager").performTouchInput { swipeRight() }
                compose.onNodeWithContentDescription("再生キュー").assertIsDisplayed()
                compose.onNodeWithTag("playerSheet").performTouchInput {
                    swipe(Offset(centerX, height * 0.2f), Offset(centerX, height * 0.27f), 300)
                }
                waitForPlayerRest()
                compose.onNodeWithTag("playerSheet").assertIsDisplayed()
                // Freeze animation time to catch a snap backwards at release (the old double transform).
                compose.mainClock.autoAdvance = false
                try {
                    compose.onNodeWithTag("playerSheet").performTouchInput {
                        down(Offset(centerX, height * 0.2f))
                        moveTo(Offset(centerX, height * 0.55f), delayMillis = 300)
                    }
                    compose.mainClock.advanceTimeByFrame()
                    var previousTop = compose.onNodeWithTag("playerSheet").fetchSemanticsNode().boundsInRoot.top
                    Assert.assertTrue("Dragging must move the sheet with the finger", previousTop > 200f)
                    compose.onNodeWithTag("playerSheet").performTouchInput { up() }
                    repeat(5) {
                        compose.mainClock.advanceTimeBy(32)
                        val top = compose.onNodeWithTag("playerSheet").fetchSemanticsNode().boundsInRoot.top
                        Assert.assertTrue("Closing must continue down from the release position", top >= previousTop - 1f)
                        previousTop = top
                    }
                    compose.mainClock.advanceTimeBy(400)
                } finally { compose.mainClock.autoAdvance = true }
                waitForPlayerGone()
                compose.onNodeWithTag("playerSheet").assertDoesNotExist()
                compose.onNodeWithTag("libraryTab-$tab").assertIsSelected()
            }
            // A normal back action must also return to the originating tab.
            openMiniPlayer()
            compose.waitForIdle()
            compose.runOnUiThread {
                compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 800f, 0.35f, BackEventCompat.EDGE_LEFT))
            }
            compose.waitForIdle()
            Assert.assertTrue(compose.onNodeWithTag("playerSheet").fetchSemanticsNode().boundsInRoot.top > 200f)
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            waitForPlayerRest()
            Assert.assertEquals(0f, compose.onNodeWithTag("playerSheet").fetchSemanticsNode().boundsInRoot.top, 1f)
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            waitForPlayerGone()
            compose.onNodeWithTag("playerSheet").assertDoesNotExist()
            compose.onNodeWithTag("libraryTab-3").assertIsSelected()
            // The full-screen overlay must still expose playback errors.
            compose.runOnUiThread { player.prepare() }
            compose.waitUntil(15_000) { connection.state.value.error != null }
            val message = requireNotNull(connection.state.value.error)
            openMiniPlayer()
            compose.onNodeWithText(message).assertIsDisplayed()
        } finally {
            compose.runOnUiThread {
                player.pause()
                player.setMediaItems(saved, savedIndex, savedPosition)
                player.shuffleModeEnabled = savedShuffle
                player.repeatMode = savedRepeat
                player.release()
            }
        }
    }
    private fun openMiniPlayer() {
        compose.onNodeWithTag("miniPlayer").performClick()
        waitForPlayerRest()
    }

    private fun waitForPlayerRest() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("playerSheet").fetchSemanticsNodes(atLeastOneRootRequired = false)
                .singleOrNull()?.boundsInRoot?.top?.let { it < 1f } == true
        }
    }

    private fun waitForPlayerGone() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("playerSheet").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
    }
}
