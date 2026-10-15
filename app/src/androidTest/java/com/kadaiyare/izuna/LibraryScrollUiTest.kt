package com.kadaiyare.izuna

import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.albumKey
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.core.model.libraryArtist
import com.kadaiyare.izuna.ui.library.LibraryBrowser
import com.kadaiyare.izuna.ui.library.LibraryState
import com.kadaiyare.izuna.ui.playlist.PlaylistViewModel
import com.kadaiyare.izuna.ui.theme.IzunaTheme
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Original long catalogs verify scrolling independently of the device's installed music. */
class LibraryScrollUiTest {
    @get:Rule(order = 0) val audioPermission = AudioPermissionRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun albumDetailsStartAtTopAndReturningRestoresTheCatalog() = checkCatalog(1, "アルバム")
    @Test fun artistDetailsStartAtTopAndReturningRestoresTheCatalog() = checkCatalog(2, "アーティスト")

    @Test fun longDetailsKeepIndependentPositionsAcrossTabsAndRepeatedOpenings() {
        val tracks = (0 until 80).flatMap { group -> (0 until 24).map { song ->
            Track("scroll-$group-$song", "test://scroll/$group/$song", "Scroll song $song",
                "Scroll artist $group", "Scroll album $group", 1000L, null, song + 1, 1, group.toLong() + 1)
        } }
        fun showFixture() {
            val lists = ViewModelProvider(compose.activity)[PlaylistViewModel::class.java]
            compose.activity.setContent {
                IzunaTheme { Surface {
                    LibraryBrowser(LibraryState(tracks), lists, true, true, null, true, 88.dp,
                        {}, {}, {}, {}, { _, _ -> fail("Browsing must not play media") }, { fail("Browsing must not loop media") })
                } }
            }
        }
        fun position(category: String) = compose.onNodeWithTag("libraryList-$category")
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        showFixture()
        listOf(1 to "アルバム", 2 to "アーティスト").forEach { (tab, category) ->
            compose.onNodeWithTag("libraryTab-$tab").performClick()
            val list = compose.onNodeWithTag("libraryList-$category")
            list.performTouchInput { swipeUp() }
            val group = tracks[40 * 24].let { if (tab == 1) it.albumKey() else it.artist }
            list.performScrollToNode(hasTestTag("libraryGroup-$group"))
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            val before = position(category)
            val header = compose.onNodeWithTag("libraryToolbar").fetchSemanticsNode().boundsInRoot.top
            val row = compose.onNodeWithTag("libraryGroup-$group").fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag("libraryGroup-$group").performClick()
            assertEquals(0f, position(category), 0.0001f)
            if (tab == 2) compose.onNodeWithTag("artistAlbum-id:41").performClick()
            assertEquals(0f, position(category), 0.0001f)
            list.performScrollToIndex(15).performTouchInput { swipeUp() }
            compose.waitForIdle()
            val detail = position(category)
            assertTrue(detail > 0f)
            run {
                // Moving to another tab and back keeps this open detail intact.
                compose.onNodeWithTag("libraryTab-0").performClick()
                compose.onNodeWithTag("libraryTab-$tab").performClick()
                assertEquals(detail, position(category), 0.0001f)
            }
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            if (tab == 2) {
                compose.onNodeWithTag("artistAlbum-id:41").assertIsDisplayed()
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            }
            android.util.Log.i("IzunaLibraryScroll", "fixture tab=$tab before=$before returned=${position(category)} headerBefore=$header headerAfter=${compose.onNodeWithTag("libraryToolbar").fetchSemanticsNode().boundsInRoot.top} rowBefore=$row rowAfter=${compose.onNodeWithTag("libraryGroup-$group").fetchSemanticsNode().boundsInRoot.top}")
            assertEquals(before, position(category), 0.0001f)
            assertEquals(header, compose.onNodeWithTag("libraryToolbar").fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals(row, compose.onNodeWithTag("libraryGroup-$group").fetchSemanticsNode().boundsInRoot.top, 1f)
            compose.onNodeWithTag("libraryGroup-$group").performClick()
            assertEquals("Reopening must start at the top again", 0f, position(category), 0.0001f)
            if (tab == 2) {
                compose.onNodeWithTag("artistAlbum-id:41").performClick()
                assertEquals("Reopening the artist album must start at the top", 0f, position(category), 0.0001f)
                compose.onNodeWithText("アルバム一覧").performClick()
            }
            compose.onNodeWithText("一覧").performClick()
            assertEquals(before, position(category), 0.0001f)
            val other = tracks[45 * 24].let { if (tab == 1) it.albumKey() else it.artist }
            list.performScrollToNode(hasTestTag("libraryGroup-$other"))
            compose.onNodeWithTag("libraryGroup-$other").performClick()
            assertEquals("Another group must not inherit the previous detail", 0f, position(category), 0.0001f)
            compose.onNodeWithText("一覧").performClick()
        }
    }

    private fun checkCatalog(tab: Int, category: String) {
        val tracks = (0 until 30).flatMap { group -> (0 until 20).map { song ->
            Track("catalog-$group-$song", "test://catalog/$group/$song", "Catalog song $song",
                "Catalog artist %02d".format(group), "Catalog album %02d".format(group), 1000, null,
                song + 1, 1, group.toLong() + 1)
        } }
        val lists = ViewModelProvider(compose.activity)[PlaylistViewModel::class.java]
        compose.activity.setContent { IzunaTheme { Surface {
            LibraryBrowser(LibraryState(tracks), lists, true, true, null, true, 88.dp,
                {}, {}, {}, {}, { _, _ -> fail("Browsing must not play media") }, { fail("Browsing must not loop media") })
        } } }
        val groups = tracks.groupBy { if (tab == 1) it.albumKey() else it.libraryArtist(false) }.keys.toList()
        compose.onNodeWithTag("libraryTab-$tab").performClick()
        val list = compose.onNodeWithTag("libraryList-$category")
        list.performTouchInput { swipeUp() }
        val target = groups[minOf(12, groups.size / 2)]
        list.performScrollToNode(hasTestTag("libraryGroup-$target"))
        compose.waitForIdle()
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val toolbarBefore = compose.onNodeWithTag("libraryToolbar").fetchSemanticsNode().boundsInRoot.top
        val rowBefore = compose.onNodeWithTag("libraryGroup-$target").fetchSemanticsNode().boundsInRoot.top
        assertTrue(before > 0f)
        capture("$tab-before")
        compose.onNodeWithTag("libraryGroup-$target").performClick()
        compose.waitForIdle()
        capture("$tab-detail")
        val detailPosition = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        android.util.Log.i("IzunaLibraryScroll", "tab=$tab catalogPosition=$before detailPosition=$detailPosition")
        try {
            assertEquals("A newly opened detail must begin at its title", 0f, detailPosition, 0.0001f)
            compose.onNodeWithTag("libraryGroupTitle-$category").assertIsDisplayed()
            list.performTouchInput { swipeUp() }
        } finally {
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        }
        compose.waitForIdle()
        capture("$tab-return")
        val restored = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        android.util.Log.i("IzunaLibraryScroll", "tab=$tab returnedPosition=$restored")
        assertEquals("Returning must preserve the catalog index and offset", before, restored, 0.0001f)
        assertEquals(toolbarBefore, compose.onNodeWithTag("libraryToolbar").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(rowBefore, compose.onNodeWithTag("libraryGroup-$target").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureLibraryScrollScreens") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        Thread.sleep(1000)
        val automation = instrumentation.uiAutomation
        val focus = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys window"))
            .bufferedReader().use { it.lineSequence().firstOrNull { line -> line.trim().startsWith("mCurrentFocus=") } }
        assertTrue(focus?.contains("${compose.activity.packageName}/") == true)
        val screenshot = automation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "library-scroll-$name.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
