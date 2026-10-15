package com.kadaiyare.izuna

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.kadaiyare.izuna.core.model.LyricsDocument
import com.kadaiyare.izuna.playback.PlaybackState
import com.kadaiyare.izuna.ui.player.LyricsScreen
import com.kadaiyare.izuna.ui.player.LyricsState
import com.kadaiyare.izuna.ui.theme.IzunaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Original fixture lyrics, no network or audio. */
class LyricsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val item = MediaItem.Builder().setMediaId("lyrics-ui").setMediaMetadata(
        MediaMetadata.Builder().setTitle("Lyrics Test").setArtist("Izuna Tests").build()).build()

    @Test fun followsRealPositionsSeeksPauseAndResumesAfterManualScrolling() {
        val document = LyricsDocument("LRCLIB", 1, null,
            (0..24).joinToString("\n") { "[00:%02d]Original fixture line $it".format(it * 2) })
        val player = mutableStateOf(PlaybackState(current = item, positionMs = 1000, playing = true))
        val state = mutableStateOf(LyricsState("test", online = true, document = document, cached = true))
        compose.activity.setContent {
            IzunaTheme { Column(Modifier.systemBarsPadding()) {
                LyricsScreen(state.value, player.value, {}, {}, {}, { delta, reset ->
                    state.value = state.value.copy(adjustmentMs = if (reset) 0 else state.value.adjustmentMs + (delta ?: 0))
                })
            } }
        }
        compose.onNodeWithTag("lyricLine-0").assertIsSelected().assertIsDisplayed()
        compose.runOnIdle { player.value = player.value.copy(positionMs = 32_000) }
        compose.onNodeWithTag("lyricLine-16").assertIsSelected().assertIsDisplayed()
        compose.runOnIdle { player.value = player.value.copy(positionMs = 4000, playing = false) }
        compose.onNodeWithTag("lyricLine-2").assertIsSelected().assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("lyricLine-2").assertIsSelected()
        compose.onNodeWithText("タイミング調整").performClick()
        compose.onNodeWithText("0.5秒遅らせる").performClick()
        compose.onNodeWithTag("lyricsAdjustment").assertTextEquals("+0.5 秒")
        compose.onNodeWithText("完了").performClick()
        compose.onNodeWithTag("lyricLine-1").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithTag("syncedLyrics").performTouchInput { swipeUp() }
        compose.onNodeWithTag("resumeLyricsFollow").assertIsDisplayed()
        compose.runOnIdle { player.value = player.value.copy(positionMs = 20_000, playing = true) }
        compose.onNodeWithTag("resumeLyricsFollow").performClick()
        compose.onNodeWithTag("lyricLine-9").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithTag("resumeLyricsFollow").assertDoesNotExist()
    }
    @Test fun unsyncedLyricsAndOfflineDisclosureRemainUsable() {
        var enabled = false
        compose.activity.setContent {
            IzunaTheme { Column(Modifier.systemBarsPadding()) {
                LyricsScreen(LyricsState("test", document = LyricsDocument("LRCLIB", 1, "Original plain line\nSecond plain line", null), cached = true),
                    PlaybackState(current = item), {}, { enabled = true }, {}, { _, _ -> })
            } }
        }
        compose.onNodeWithTag("plainLyrics").assertIsDisplayed()
        compose.onNodeWithText("Original plain line").assertIsDisplayed()
        compose.onNodeWithTag("syncedLyrics").assertDoesNotExist()
        compose.onNodeWithTag("enableOnlineLyrics").performClick()
        assertTrue(enabled)
        compose.onNodeWithText("LRCLIB · 同期なし · 保存済み").assertIsDisplayed()
    }
}
