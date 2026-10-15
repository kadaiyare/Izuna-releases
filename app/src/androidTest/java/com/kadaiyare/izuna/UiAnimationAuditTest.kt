package com.kadaiyare.izuna

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.data.playlist.PlaylistRepository
import com.kadaiyare.izuna.playback.PlaybackConnection
import com.kadaiyare.izuna.playback.PlaybackState
import com.kadaiyare.izuna.ui.library.LibraryScreen
import com.kadaiyare.izuna.ui.library.LibraryState
import com.kadaiyare.izuna.ui.player.PlayerScreen
import com.kadaiyare.izuna.ui.player.PlayerSheet
import com.kadaiyare.izuna.ui.playlist.PlaylistScreen
import com.kadaiyare.izuna.ui.playlist.PlaylistViewModel
import com.kadaiyare.izuna.ui.theme.IzunaTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Original fixtures and isolated playlist storage; no audio, network or user-data deletion. */
class UiAnimationAuditTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun landscapeDetailsScrollDownWithoutDismissingThePlayer() {
        val open = mutableStateOf(true)
        val item = MediaItem.Builder().setMediaId("test-landscape").setMediaMetadata(MediaMetadata.Builder()
            .setTitle("A long original title for checking the landscape player controls")
            .setArtist("An original artist name that wraps over several lines")
            .setAlbumTitle("Landscape layout fixture").build()).build()
        val connection = PlaybackConnection(compose.activity)
        compose.activity.setContent { IzunaTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    PlayerSheet(open.value, { open.value = false }) {
                        PlayerScreen(PlaybackState(current = item), connection, { open.value = false }, {}, onLyrics = {})
                    }
                }
            }
        } }
        val details = compose.onNodeWithTag("playerDetailsScroll")
        details.performTouchInput { swipeUp() }
        assertTrue(details.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        // Begin in the upper half of the scrolled controls, rather than on the artwork or toolbar.
        details.performTouchInput { swipeDown(startY = height * 0.15f, endY = height * 0.9f) }
        compose.runOnIdle { assertTrue("Browsing the controls must not close the player", open.value) }
        compose.onNodeWithTag("playerSheet").assertIsDisplayed()
        assertEquals(0f, compose.onNodeWithTag("playerSheet").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun playlistDetailsStartAtTopAndBackRestoresCatalogPosition() {
        val job = SupervisorJob()
        val file = File(compose.activity.cacheDir, "ui-audit-${System.nanoTime()}.preferences_pb")
        val repository = PlaylistRepository.create(file, CoroutineScope(job + Dispatchers.IO))
        val model = PlaylistViewModel(compose.activity.application as Application, repository)
        val tracks = (0..39).map { index -> Track("audit-$index", "test://audit/$index", "Fixture song $index",
            "Fixture artist", "Fixture album", 12000, null, index + 1, 1) }
        try {
            runBlocking {
                repeat(50) { repository.create("Audit playlist %02d".format(it)) }
                val target = repository.data.first()[30]
                repository.addTracks(target.id, tracks.map { it.uri })
            }
            compose.activity.setContent { IzunaTheme {
                Column(Modifier.systemBarsPadding()) {
                    PlaylistScreen(model, LibraryState(tracks), true, true, true, { _, _ -> fail("Browsing must not play media") }, showHeading = false)
                }
            } }
            compose.waitUntil(10000) { !model.state.value.loading }
            val target = model.state.value.lists[30]
            val list = compose.onNodeWithTag("playlistList")
            val row = compose.onNodeWithTag("playlist-${target.id}")
            list.performScrollToNode(hasTestTag("playlist-${target.id}"))
            fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val before = position()
            val beforeTop = row.fetchSemanticsNode().boundsInRoot.top
            assertTrue(before > 0f)
            row.performClick()
            assertEquals("Opening a playlist must show its first song and actions", 0f, position(), 0.0001f)
            list.performScrollToIndex(22)
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals("Back must restore the catalog's index and offset", before, position(), 0.0001f)
            assertEquals(beforeTop, row.fetchSemanticsNode().boundsInRoot.top, 1f)
            row.performClick()
            assertEquals("Reopening must start at the top", 0f, position(), 0.0001f)
        } finally { model.viewModelScope.cancel(); runBlocking { job.cancelAndJoin() }; file.delete() }
    }

    @Test fun confirmingPlaylistAddClosesTheSheetWithAnAnimation() {
        val job = SupervisorJob()
        val file = File(compose.activity.cacheDir, "ui-sheet-audit-${System.nanoTime()}.preferences_pb")
        val repository = PlaylistRepository.create(file, CoroutineScope(job + Dispatchers.IO))
        val model = PlaylistViewModel(compose.activity.application as Application, repository)
        val track = Track("audit-add", "test://audit/add", "Original add fixture", "Fixture artist", "Fixture album", 12000, null, 1, 1)
        try {
            runBlocking { repository.create("Sheet animation fixture") }
            compose.activity.setContent { IzunaTheme {
                Column(Modifier.systemBarsPadding()) {
                    PlaylistScreen(model, LibraryState(listOf(track)), true, true, true, { _, _ -> fail("Browsing must not play media") }, showHeading = false)
                }
            } }
            compose.waitUntil(10000) { !model.state.value.loading }
            compose.onNodeWithText("Sheet animation fixture").performClick()
            compose.onNodeWithText("曲を追加").performClick()
            compose.onNodeWithText(track.title).performClick()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            try {
                var previousTop = compose.onNodeWithTag("playlistAddSheet").fetchSemanticsNode().boundsInRoot.top
                compose.onNodeWithText("追加 (1)").performClick()
                repeat(4) {
                    compose.mainClock.advanceTimeBy(32)
                    val top = compose.onNodeWithTag("playlistAddSheet").fetchSemanticsNode().boundsInRoot.top
                    assertTrue("The add sheet should slide down before it is removed", top >= previousTop - 1f)
                    previousTop = top
                }
                compose.mainClock.advanceTimeBy(1000)
                compose.onNodeWithTag("playlistAddSheet").assertDoesNotExist()
            } finally { compose.mainClock.autoAdvance = true }
            compose.waitUntil(10000) { model.state.value.lists.single().trackUris == listOf(track.uri) }
            compose.onNodeWithText(track.title).assertIsDisplayed()
        } finally { model.viewModelScope.cancel(); runBlocking { job.cancelAndJoin() }; file.delete() }
    }

    @Test fun albumDetailAndPlaybackQueueFollowDiscAndTrackOrder() {
        val tracks = listOf(
            Track("second", "test://second", "Second song", "Artist", "Album", 12000, null, 2, 1, 1),
            Track("disc-two", "test://disc-two", "Disc two song", "Artist", "Album", 12000, null, 1, 2, 1),
            Track("first", "test://first", "First song", "Artist", "Album", 12000, null, 1, 1, 1))
        var queue = emptyList<Track>()
        var selected = -1
        compose.activity.setContent { IzunaTheme {
            LibraryScreen(LibraryState(tracks), true, true, null, {}, {}, {}, {},
                { songs, index -> queue = songs; selected = index }, {}, category = "アルバム")
        } }
        compose.onNodeWithTag("libraryGroup-id:1").performClick()
        val positions = listOf("First song", "Second song", "Disc two song").map {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("Album details must follow disc number, then track number", positions[0] < positions[1] && positions[1] < positions[2])
        compose.onNode(hasText("Second song") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf("first", "second", "disc-two"), queue.map { it.id }); assertEquals(1, selected) }
    }
}
