package com.kadaiyare.izuna

import androidx.activity.compose.setContent
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.ListeningSession
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.playback.PlaybackState
import com.kadaiyare.izuna.ui.history.HistoryState
import com.kadaiyare.izuna.ui.library.LibraryState
import com.kadaiyare.izuna.ui.player.QueueTimeline
import com.kadaiyare.izuna.ui.theme.IzunaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** In-memory fixtures only: no history DB writes, playback or external sharing. */
class QueueTimelineUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun track(index: Int) = Track("$index", "test://$index", "History $index", "Test Artist",
        "Test Album", 180_000, null, index, 1)
    private fun media(title: String) = MediaItem.Builder().setMediaId(title).setUri("test://$title")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Test Artist").build()).build()

    @Test fun currentAnchorsBetweenHistoryAndQueueAndOlderHistoryAppearsOnScroll() {
        val tracks = (0..59).map(::track)
        val finished = tracks.mapIndexed { i, track -> ListeningSession("$i", track,
            startedAtMs = i * 180_000L, updatedAtMs = (i + 1) * 180_000L,
            endedAtMs = (i + 1) * 180_000L, listenedMs = 180_000) }
        val current = media("Current")
        val queue = listOf(media("Already passed"), current, media("Next"), media("Later"))
        val history = mutableStateOf(HistoryState(loading = true))
        var selected: Int? = null
        var replayed: Track? = null
        val scroll = LazyListState()
        compose.activity.setContent {
            IzunaTheme {
                Column(Modifier.systemBarsPadding()) {
                    QueueTimeline(PlaybackState(connected = true, current = current, queue = queue,
                        currentIndex = 1, upcomingIndices = listOf(2, 3)), history.value,
                        LibraryState(tracks = tracks), true, { selected = it }, { replayed = it }, {}, {}, scroll)
                }
            }
        }
        // Initial asynchronous history loading must not strand the viewport at the oldest row.
        compose.runOnIdle { history.value = HistoryState(entries = finished.reversed() +
            ListeningSession("active", tracks.last(), 11_000_000, 11_000_010), recording = true) }
        compose.onNodeWithTag("currentQueueItem").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("history-59").assertIsDisplayed()
        compose.onNodeWithTag("queue-2").assertIsDisplayed()
        compose.onNodeWithTag("history-59").assertTextEquals("History 59", "Test Artist")
        compose.onNodeWithTag("queue-2").assertTextEquals("Next", "Test Artist")
        val previousBounds = compose.onNodeWithTag("history-59").fetchSemanticsNode().boundsInRoot
        val currentBounds = compose.onNodeWithTag("currentQueueItem").fetchSemanticsNode().boundsInRoot
        val nextBounds = compose.onNodeWithTag("queue-2").fetchSemanticsNode().boundsInRoot
        assertTrue(previousBounds.bottom <= currentBounds.top)
        assertTrue(currentBounds.bottom <= nextBounds.top)
        compose.onNodeWithText("Already passed").assertDoesNotExist()
        compose.onNodeWithTag("history-active").assertDoesNotExist()
        val list = compose.onNodeWithTag("queueHistoryList")
        val initialCount = scroll.layoutInfo.totalItemsCount
        assertTrue("Older history must not all be added initially", initialCount < finished.size)
        // Reaching the top adds an older window while retaining the visible history row's key.
        list.performScrollToIndex(2)
        val anchor = scroll.layoutInfo.visibleItemsInfo.first { it.key.toString().startsWith("history-") }
        list.performTouchInput {
            down(center)
            moveBy(Offset(0f, 40f), delayMillis = 300)
        }
        try {
            compose.waitUntil(5_000) { scroll.layoutInfo.totalItemsCount > initialCount }
            val retained = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor.key }
            assertNotNull("Prepending history must retain the visible song", retained)
            assertEquals("Load just one older window", initialCount + 20, scroll.layoutInfo.totalItemsCount)
            assertTrue("Prepending must preserve the drag displacement", requireNotNull(retained).offset - anchor.offset in 0..60)
            // The same finger stays down across the prepend; its next movement must still scroll.
            val afterPrepend = retained.offset
            list.performTouchInput { moveBy(Offset(0f, 40f), delayMillis = 300) }
            compose.waitForIdle()
            val continued = scroll.layoutInfo.visibleItemsInfo.first { it.key == anchor.key }
            assertTrue("Prepending must not cancel an ongoing drag", continued.offset - afterPrepend in 25..55)
        } finally { list.performTouchInput { up() } }
        list.performScrollToNode(hasTestTag("history-39"))
        compose.onNodeWithTag("history-39").assertIsDisplayed().performClick()
        assertEquals(tracks[39], replayed)
        list.performScrollToIndex(2)
        list.performTouchInput { swipeDown() }
        list.performScrollToNode(hasTestTag("history-0"))
        compose.onNodeWithTag("history-0").assertIsDisplayed()
        list.performScrollToIndex(0)
        list.performTouchInput { swipeDown(durationMillis = 400) }
        compose.waitUntil(5_000) { !scroll.isScrollInProgress }
        val oldestBounds = compose.onNodeWithTag("history-0").fetchSemanticsNode().boundsInRoot
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val topMargin = 8 * compose.activity.resources.displayMetrics.density
        assertEquals("The oldest history must stay near the top after all batches load",
            listBounds.top + topMargin, oldestBounds.top, 2f)
        captureTimeline("queue-history-start-test.png")
        list.performScrollToNode(hasTestTag("currentQueueItem"))
        compose.onNodeWithTag("currentQueueItem").assertIsSelected()
        list.performScrollToNode(hasTestTag("queue-2"))
        compose.onNodeWithTag("queue-2").performClick()
        assertEquals(2, selected)
    }

    @Test fun currentIsCentredWithManyHistoryEntriesAndFewOrNoUpcomingSongsOnEveryOpen() {
        val tracks = (0..99).map(::track)
        val finished = tracks.mapIndexed { i, track -> ListeningSession("$i", track,
            i * 180_000L, (i + 1) * 180_000L, (i + 1) * 180_000L, 180_000) }
        val current = media("Centred Current")
        val history = mutableStateOf(HistoryState(entries = finished))
        val playback = mutableStateOf(PlaybackState(connected = true, current = current, queue = listOf(current)))
        val active = mutableStateOf(false)
        val scroll = LazyListState()
        compose.activity.setContent {
            IzunaTheme {
                Column(Modifier.systemBarsPadding()) {
                    QueueTimeline(playback.value, history.value, LibraryState(tracks = tracks), true,
                        {}, {}, {}, {}, scroll, active.value)
                }
            }
        }
        fun assertCentred() {
            try {
                compose.waitUntil(5_000) {
                    val row = compose.onAllNodesWithTag("currentQueueItem").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                    val list = compose.onNodeWithTag("queueHistoryList").fetchSemanticsNode().boundsInRoot
                    row != null && kotlin.math.abs(row.center.y - list.center.y) < 3f
                }
            } catch (failure: ComposeTimeoutException) {
                val row = compose.onAllNodesWithTag("currentQueueItem").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                val list = compose.onNodeWithTag("queueHistoryList").fetchSemanticsNode().boundsInRoot
                throw AssertionError("Current bounds=$row, viewport=$list, first=${scroll.firstVisibleItemIndex}, " +
                    "offset=${scroll.firstVisibleItemScrollOffset}, current=" +
                    scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "current" }?.offset, failure)
            }
            compose.onNodeWithTag("currentQueueItem").assertIsSelected().assertIsDisplayed()
        }
        listOf(0, 1, 2, 24).forEach { count ->
            compose.runOnIdle { active.value = false }
            compose.runOnIdle {
                playback.value = playback.value.copy(queue = listOf(current) + (1..count).map { media("Next $it") },
                    upcomingIndices = (1..count).toList())
                active.value = true
            }
            assertCentred()
            if (count == 0) {
                captureTimeline("queue-centred-test.png")
            }
            assertTrue("Do not eagerly reveal all history", scroll.layoutInfo.totalItemsCount < 100)
            val list = compose.onNodeWithTag("queueHistoryList")
            list.performTouchInput { swipeDown(startY = height * 0.4f, endY = height * 0.7f, durationMillis = 400) }
            compose.waitUntil(5_000) { !scroll.isScrollInProgress }
            compose.waitForIdle()
            val anchor = scroll.layoutInfo.visibleItemsInfo.first()
            compose.runOnIdle { playback.value = playback.value.copy(playing = !playback.value.playing) }
            val retained = scroll.layoutInfo.visibleItemsInfo.first { it.key == anchor.key }
            assertEquals("Player updates must not recenter a browsed list", anchor.offset, retained.offset)
            compose.runOnIdle { active.value = false }
            compose.runOnIdle { active.value = true }
            assertCentred()
        }
        compose.runOnIdle { active.value = false }
        compose.runOnIdle {
            history.value = HistoryState()
            playback.value = playback.value.copy(queue = listOf(current), upcomingIndices = emptyList())
            active.value = true
        }
        assertCentred()
    }

    @Test fun shortHistoryAndHistoryWithoutCurrentAlsoStartNearTheTop() {
        val tracks = (0..2).map(::track)
        val finished = tracks.mapIndexed { i, track -> ListeningSession("$i", track,
            i * 180_000L, (i + 1) * 180_000L, (i + 1) * 180_000L, 180_000) }
        val history = mutableStateOf(HistoryState(entries = finished.take(1)))
        val current = media("Current")
        val playback = mutableStateOf(PlaybackState(connected = true, current = current, queue = listOf(current)))
        val scroll = LazyListState()
        compose.activity.setContent {
            IzunaTheme {
                Column(Modifier.systemBarsPadding()) {
                    QueueTimeline(playback.value, history.value, LibraryState(tracks = tracks), true,
                        {}, {}, {}, {}, scroll)
                }
            }
        }
        listOf(1, 3).forEach { count ->
            listOf(true, false).forEach { hasCurrent ->
                compose.runOnIdle {
                    history.value = HistoryState(entries = finished.take(count))
                    playback.value = if (hasCurrent) PlaybackState(connected = true, current = current, queue = listOf(current))
                        else PlaybackState(connected = true)
                }
                val list = compose.onNodeWithTag("queueHistoryList")
                list.performScrollToIndex(0)
                val oldest = compose.onNodeWithTag("history-0").fetchSemanticsNode().boundsInRoot
                val viewport = list.fetchSemanticsNode().boundsInRoot
                assertEquals("A short history must not inherit the current song's centring margin",
                    viewport.top + 8 * compose.activity.resources.displayMetrics.density, oldest.top, 2f)
            }
        }
    }

    private fun captureTimeline(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureQueueScreens") != "true") return
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val focus = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys window"))
            .bufferedReader().use { reader -> reader.lineSequence().firstOrNull { it.trim().startsWith("mCurrentFocus=") } }
        assertTrue("Screenshots require Izuna in the foreground", focus?.contains("${compose.activity.packageName}/") == true)
        val screenshot = automation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    @Test fun loadingOlderHistoryAtTheVeryStartKeepsTheOldFirstSongAndContinuesTheGesture() {
        val tracks = (0..99).map(::track)
        val history = HistoryState(entries = tracks.mapIndexed { i, track -> ListeningSession("$i", track,
            i * 180_000L, (i + 1) * 180_000L, (i + 1) * 180_000L, 180_000) })
        val current = media("Current")
        val scroll = LazyListState()
        compose.activity.setContent {
            IzunaTheme {
                // The native edge stretch keeps drawing while a pointer is held at the boundary.
                // Isolate key/drag preservation so Espresso can inspect it before releasing the finger.
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    Column(Modifier.systemBarsPadding()) {
                        QueueTimeline(PlaybackState(connected = true, current = current, queue = listOf(current)),
                            history, LibraryState(tracks = tracks), true, {}, {}, {}, {}, scroll)
                    }
                }
            }
        }
        val list = compose.onNodeWithTag("queueHistoryList")
        compose.onNodeWithTag("currentQueueItem").assertIsDisplayed()
        compose.waitUntil(5_000) { !scroll.isScrollInProgress }
        // Start at the very first row, as after a fast fling hits the loaded window's boundary.
        // Position it before touching: a test scroll command must not cancel the gesture under test.
        compose.runOnUiThread { scroll.requestScrollToItem(0) }
        compose.waitForIdle()
        val firstSong = scroll.layoutInfo.visibleItemsInfo.first { it.key == "history-80" }
        val count = scroll.layoutInfo.totalItemsCount
        list.performTouchInput { down(center); moveBy(Offset(0f, 40f), delayMillis = 300) }
        try {
            compose.waitUntil(5_000) { scroll.layoutInfo.totalItemsCount > count }
            val retained = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == firstSong.key }
            assertNotNull("The former first song must stay visible", retained)
            assertTrue("Index-zero prepends must not jump to the oldest added song",
                kotlin.math.abs(requireNotNull(retained).offset - firstSong.offset) <= 60)
        } finally { list.performTouchInput { up() } }
    }

    @Test fun queueUsesServiceOrderAndUnavailableHistoryCannotBePlayed() {
        val queue = (0..3).map { media("Queue $it") }
        var played = false
        compose.activity.setContent {
            IzunaTheme {
                Column(Modifier.systemBarsPadding()) {
                    QueueTimeline(PlaybackState(connected = true, current = queue[1], queue = queue,
                        currentIndex = 1, shuffle = true, upcomingIndices = listOf(3, 0, 2)),
                        HistoryState(entries = listOf(ListeningSession("missing", track(0), 1, 2, 2)), recording = false),
                        LibraryState(), true, {}, { played = true }, {}, {})
                }
            }
        }
        val first = compose.onNodeWithTag("queue-3").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("queue-0").fetchSemanticsNode().boundsInRoot
        assertTrue(first.bottom <= second.top)
        compose.onNodeWithTag("history-missing").assertIsNotEnabled()
        compose.onNodeWithTag("history-missing").performClick()
        assertFalse(played)
        compose.onNodeWithTag("history-missing").assertTextContains("ファイルが見つかりません")
    }
}
