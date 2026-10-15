package com.kadaiyare.izuna

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.ui.library.LibraryScreen
import com.kadaiyare.izuna.ui.library.LibraryState
import com.kadaiyare.izuna.ui.theme.IzunaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** In-memory catalog only; verifies playback callbacks without playing or changing media. */
class LibraryAlbumSectionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val tracks = listOf(
        Track("z", "test://z", "Zebra song", "Band", "Zebra album", 1000, null, 1, 1, 20),
        Track("a2", "test://a2", "Apple second", "Band", "Apple album", 1000, null, 2, 1, 10),
        Track("a1", "test://a1", "Apple first", "Band", "Apple album", 1000, null, 1, 1, 10),
        Track("ad2", "test://ad2", "Apple disc two", "Band", "Apple album", 1000, null, 1, 2, 10),
    )

    @Test fun artistAlbumSummaryKeepsCountReadableAtNarrowWidthsAndLargeText() {
        val width = mutableStateOf(320.dp)
        val fontScale = mutableStateOf(1f)
        val group = mutableStateOf<String?>("Band")
        val album = mutableStateOf<String?>("id:10")
        compose.activity.setContent { IzunaTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                Box(Modifier.width(width.value)) {
                    LibraryScreen(LibraryState(tracks), true, true, null, {}, {}, {}, {},
                        { _, _ -> }, {}, category = "アーティスト", selectedGroup = group, selectedArtistAlbum = album)
                }
            }
        } }
        listOf(320 to 1f, 360 to 1.5f, 320 to 1.5f).forEach { (dpWidth, scale) ->
            compose.runOnIdle { width.value = dpWidth.dp; fontScale.value = scale }
            val count = compose.onNodeWithText("3曲").assertIsDisplayed()
            val layout = mutableListOf<TextLayoutResult>()
            count.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            assertEquals("Short count must remain on one line at $dpWidth dp / $scale", 1, layout.single().lineCount)
            val measured = layout.single()
            assertTrue("Count must not overflow at $dpWidth dp / $scale: size=${measured.size}, " +
                "widthOverflow=${measured.didOverflowWidth}, heightOverflow=${measured.didOverflowHeight}, " +
                "lineRight=${measured.getLineRight(0)}, lineBottom=${measured.getLineBottom(0)}", !measured.hasVisualOverflow)
            val bounds = count.fetchSemanticsNode().boundsInRoot
            listOf("アルバム一覧", "ループ", "すべて再生").forEach { label ->
                val button = compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("Count must not overlap $label", !bounds.overlaps(button))
            }
        }
    }

    @Test fun artistAlbumButtonsOpenOnlySelectedAlbumAndKeepPlaybackOrder() {
        var queue = emptyList<Track>()
        var selected = -1
        var loop = emptyList<Track>()
        compose.activity.setContent { IzunaTheme {
            LibraryScreen(LibraryState(tracks), true, true, null, {}, {}, {}, {},
                { songs, index -> queue = songs; selected = index }, { loop = it }, category = "アーティスト")
        } }
        compose.onNodeWithTag("libraryGroup-Band").performClick()
        val firstButton = compose.onNodeWithTag("artistAlbum-id:10").assertHasClickAction().fetchSemanticsNode().boundsInRoot.top
        val secondButton = compose.onNodeWithTag("artistAlbum-id:20").assertHasClickAction().fetchSemanticsNode().boundsInRoot.top
        assertTrue(firstButton < secondButton)
        compose.onNodeWithText("Apple first").assertDoesNotExist()
        compose.onNodeWithText("すべて再生").performClick()
        compose.runOnIdle { assertEquals(listOf("a1", "a2", "ad2", "z"), queue.map { it.id }); assertEquals(0, selected) }
        compose.onNodeWithTag("artistAlbum-id:20").performClick()
        compose.onNodeWithText("Apple first").assertDoesNotExist()
        compose.onNode(hasText("Zebra song") and hasClickAction()).performClick()
        compose.runOnIdle {
            assertEquals(listOf("z"), queue.map { it.id })
            assertEquals(0, selected)
        }
        compose.onNodeWithText("アルバム一覧").performClick()
        compose.onNodeWithTag("artistAlbum-id:10").performClick()
        compose.onNodeWithText("Zebra song").assertDoesNotExist()
        val songPositions = listOf("Apple first", "Apple second", "Apple disc two").map {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue(songPositions[0] < songPositions[1] && songPositions[1] < songPositions[2])
        compose.onNode(hasText("Apple second") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf("a1", "a2", "ad2"), queue.map { it.id }); assertEquals(1, selected) }
        compose.onNodeWithText("すべて再生").performClick()
        compose.runOnIdle {
            assertEquals(listOf("a1", "a2", "ad2"), queue.map { it.id })
            assertEquals(0, selected)
        }
        compose.onNodeWithText("ループ").performClick()
        compose.runOnIdle { assertEquals(listOf("a1", "a2", "ad2"), loop.map { it.id }) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("artistAlbum-id:10").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("libraryGroup-Band").assertIsDisplayed()
    }

    @Test fun albumCatalogUsesNameOrder() {
        compose.activity.setContent { IzunaTheme {
            LibraryScreen(LibraryState(tracks), true, true, null, {}, {}, {}, {},
                { _, _ -> }, {}, category = "アルバム")
        } }
        assertTrue(compose.onNodeWithTag("libraryGroup-id:10").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("libraryGroup-id:20").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun artistCatalogUsesNameOrderRatherThanAlbumOrSourceOrder() {
        val catalog = tracks.take(3).zip(listOf("Zulu", "beta", "Alpha")) { track, artist -> track.copy(artist = artist) }
        compose.activity.setContent { IzunaTheme {
            LibraryScreen(LibraryState(catalog), true, true, null, {}, {}, {}, {},
                { _, _ -> }, {}, category = "アーティスト")
        } }
        val positions = listOf("Alpha", "beta", "Zulu").map {
            compose.onNodeWithTag("libraryGroup-$it").fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue(positions[0] < positions[1] && positions[1] < positions[2])
    }

    @Test fun changingSearchWithinArtistOnlyShowsAndQueuesMatchingAlbumsAndSongs() {
        val query = mutableStateOf("")
        var queue = emptyList<Track>()
        var selected = -1
        compose.activity.setContent { IzunaTheme {
            LibraryScreen(LibraryState(tracks), true, true, null, {}, {}, {}, {},
                { songs, index -> queue = songs; selected = index }, {},
                category = "アーティスト", query = query.value)
        } }
        compose.onNodeWithTag("libraryGroup-Band").performClick()
        compose.runOnIdle { query.value = "Zebra" }
        compose.onNodeWithTag("artistAlbum-id:10").assertDoesNotExist()
        compose.onNodeWithTag("artistAlbum-id:20").assertIsDisplayed()
        compose.onNodeWithTag("artistAlbum-id:20").performClick()
        compose.onNodeWithText("すべて再生").performClick()
        compose.runOnIdle { assertEquals(listOf("z"), queue.map { it.id }); assertEquals(0, selected) }
        compose.runOnIdle { query.value = "Apple first" }
        compose.onNodeWithText("曲が見つかりません").assertIsDisplayed()
        compose.onNodeWithText("Apple first").assertDoesNotExist()
        compose.onNodeWithText("アルバム一覧").performClick()
        compose.onNodeWithTag("artistAlbum-id:10").performClick()
        compose.onNode(hasText("Apple first") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf("a1"), queue.map { it.id }); assertEquals(0, selected) }
    }

    @Test fun returningFromAlbumRestoresAlbumButtonPositionAndReopeningStartsAtTop() {
        val catalog = (1..40).flatMap { album -> (1..24).map { song ->
            Track("$album-$song", "test://$album/$song", "Song $song", "Band", "Album %02d".format(album),
                1000, null, song, 1, album.toLong())
        } }
        compose.activity.setContent { IzunaTheme {
            LibraryScreen(LibraryState(catalog), true, true, null, {}, {}, {}, {},
                { _, _ -> }, {}, category = "アーティスト")
        } }
        compose.onNodeWithTag("libraryGroup-Band").performClick()
        val list = compose.onNodeWithTag("libraryList-アーティスト")
        list.performScrollToNode(hasTestTag("artistAlbum-id:25"))
        compose.waitForIdle()
        fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = position()
        val rowBefore = compose.onNodeWithTag("artistAlbum-id:25").fetchSemanticsNode().boundsInRoot.top
        assertTrue(before > 0f)
        compose.onNodeWithTag("artistAlbum-id:25").performClick()
        assertEquals(0f, position(), 0.0001f)
        list.performScrollToIndex(15)
        assertTrue(position() > 0f)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(before, position(), 0.0001f)
        assertEquals(rowBefore, compose.onNodeWithTag("artistAlbum-id:25").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("artistAlbum-id:25").performClick()
        assertEquals(0f, position(), 0.0001f)
        compose.onNodeWithText("アルバム一覧").performClick()
        assertEquals(before, position(), 0.0001f)
    }
}
