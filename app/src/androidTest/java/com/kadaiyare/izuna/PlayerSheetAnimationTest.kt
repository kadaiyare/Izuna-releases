package com.kadaiyare.izuna

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.kadaiyare.izuna.playback.PlaybackConnection
import com.kadaiyare.izuna.playback.PlaybackState
import com.kadaiyare.izuna.ui.player.PlayerScreen
import com.kadaiyare.izuna.ui.player.PlayerSheet
import com.kadaiyare.izuna.ui.theme.IzunaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Checks individual animation frames with paused original fixture metadata; never prepares audio. */
class PlayerSheetAnimationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val open = mutableStateOf(true)
    private val landscape = mutableStateOf(false)
    private var dismissals = 0

    private fun showPlayer() {
        val item = MediaItem.Builder().setMediaId("test-animation").setMediaMetadata(MediaMetadata.Builder()
            .setTitle("Original animation fixture").setArtist("Izuna Tests").build()).build()
        val playback = PlaybackConnection(compose.activity)
        compose.activity.setContent { IzunaTheme {
            Box(if (landscape.value) Modifier.fillMaxWidth().height(360.dp) else Modifier.fillMaxSize()) {
                Text("Underlying library")
                PlayerSheet(open.value, { dismissals++; open.value = false }) {
                    PlayerScreen(PlaybackState(current = item), playback, { open.value = false }, {})
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun beginDrag(fraction: Float) {
        val sheet = compose.onNodeWithTag("playerSheet").fetchSemanticsNode().boundsInRoot
        val artwork = compose.onNodeWithTag("playerArtwork").fetchSemanticsNode().boundsInRoot
        val start = artwork.center - sheet.topLeft
        compose.onNodeWithTag("playerSheet").performTouchInput {
            down(start)
            moveTo(start + Offset(0f, height * fraction), delayMillis = 300)
        }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("Artwork drag must follow the finger", top() > 20f)
    }

    private fun top() = compose.onNodeWithTag("playerSheet").fetchSemanticsNode().boundsInRoot.top

    @Test fun shortArtworkDragReturnsContinuouslyInPortraitAndLandscape() {
        showPlayer()
        repeat(2) { layout ->
            if (layout == 1) compose.runOnIdle { landscape.value = true }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            try {
                beginDrag(0.08f)
                var previous = top()
                compose.onNodeWithTag("playerSheet").performTouchInput { up() }
                repeat(5) {
                    compose.mainClock.advanceTimeBy(32)
                    val current = top()
                    assertTrue("Short drag must return upwards: layout=$layout previous=$previous current=$current", current <= previous + 1f)
                    previous = current
                }
                compose.mainClock.advanceTimeBy(400)
                assertEquals(0f, top(), 1f)
                assertTrue(open.value)
                assertEquals(0, dismissals)
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test fun cancelledTouchReturnsToRestWithoutDismissing() {
        showPlayer()
        compose.mainClock.autoAdvance = false
        try {
            beginDrag(0.3f)
            compose.onNodeWithTag("playerSheet").performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(500)
            assertTrue("An interrupted touch must not dismiss", open.value)
            assertEquals(0, dismissals)
            assertEquals(0f, top(), 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun closingContinuesFromReleaseAndCanReverseToOpenWithoutLeavingAnOverlay() {
        showPlayer()
        compose.mainClock.autoAdvance = false
        try {
            beginDrag(0.3f)
            var previous = top()
            compose.onNodeWithTag("playerSheet").performTouchInput { up() }
            repeat(4) {
                compose.mainClock.advanceTimeBy(32)
                val current = top()
                assertTrue("Closing must continue down from the release point", current >= previous - 1f)
                previous = current
            }
            assertFalse(open.value)
            assertEquals(1, dismissals)
            compose.runOnUiThread { open.value = true }
            compose.mainClock.advanceTimeBy(500)
            assertEquals(0f, top(), 1f)
            compose.runOnUiThread { open.value = false }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("playerSheet").assertDoesNotExist()
            compose.onNodeWithText("Underlying library").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
    }
}
