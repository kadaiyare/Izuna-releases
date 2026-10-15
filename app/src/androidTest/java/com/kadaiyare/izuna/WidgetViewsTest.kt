package com.kadaiyare.izuna

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.data.artwork.loadArtwork
import com.kadaiyare.izuna.widget.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetViewsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun emptyAndPermissionStatesOpenAppAndDisableNavigation() {
        instrumentation.runOnMainSync {
            for (state in listOf(WidgetState(), WidgetState(title = "Song", hasTrack = true, status = WidgetStatus.Permission))) {
                val view = nowPlayingWidgetViews(context, 1, state, null).apply(context, FrameLayout(context))
                assertFalse(view.findViewById<ImageButton>(R.id.widget_previous).isEnabled)
                assertFalse(view.findViewById<ImageButton>(R.id.widget_next).isEnabled)
                assertEquals(context.getString(R.string.widget_open_app), view.findViewById<ImageButton>(R.id.widget_toggle).contentDescription)
                assertNotNull(view.findViewById<ImageView>(R.id.widget_artwork).drawable)
            }
        }
    }

    @Test fun bufferingCanBePausedAndLongTagsAreConstrainedToOneLine() {
        instrumentation.runOnMainSync {
            val state = WidgetState(title = "長い曲名".repeat(100), artist = "歌手".repeat(100), hasTrack = true,
                pauseAction = true, next = true, status = WidgetStatus.Buffering)
            val view = nowPlayingWidgetViews(context, 2, state, null).apply(context, FrameLayout(context))
            assertEquals(context.getString(R.string.widget_pause), view.findViewById<ImageButton>(R.id.widget_toggle).contentDescription)
            assertEquals(context.getString(R.string.widget_status_buffering), view.findViewById<TextView>(R.id.widget_status).text)
            assertFalse(view.findViewById<ImageButton>(R.id.widget_previous).isEnabled)
            assertTrue(view.findViewById<ImageButton>(R.id.widget_next).isEnabled)
            assertEquals(1, view.findViewById<TextView>(R.id.widget_title).maxLines)
            assertEquals(1, view.findViewById<TextView>(R.id.widget_artist).maxLines)
        }
    }

    @Test fun artworkAndStoppedStateDoNotLeaveOldPaddingOrPauseAction() {
        instrumentation.runOnMainSync {
            val state = WidgetState(title = "Song", hasTrack = true, pauseAction = true, status = WidgetStatus.Playing)
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val view = nowPlayingWidgetViews(context, 3, state, bitmap).apply(context, FrameLayout(context))
            assertEquals(0, view.findViewById<ImageView>(R.id.widget_artwork).paddingLeft)
            nowPlayingWidgetViews(context, 3, state.stopped(), null).reapply(context, view)
            assertTrue(view.findViewById<ImageView>(R.id.widget_artwork).paddingLeft > 0)
            assertEquals(context.getString(R.string.widget_play), view.findViewById<ImageButton>(R.id.widget_toggle).contentDescription)
            assertEquals(context.getString(R.string.widget_status_paused), view.findViewById<TextView>(R.id.widget_status).text)
        }
    }

    @Test fun distinctImmutablePendingIntentsSeparateWidgetsAndCommands() {
        val first = widgetControlIntent(context, 1, WidgetAction.Toggle)
        assertTrue(first.isImmutable)
        assertEquals(first, widgetControlIntent(context, 1, WidgetAction.Toggle))
        assertNotEquals(first, widgetControlIntent(context, 2, WidgetAction.Toggle))
        assertNotEquals(first, widgetControlIntent(context, 1, WidgetAction.Next))
    }

    @Test fun serviceClosurePreservesPermissionAndPlaybackErrors() {
        for (status in listOf(WidgetStatus.Permission, WidgetStatus.Unavailable, WidgetStatus.Error, WidgetStatus.Ended)) {
            val state = WidgetState(title = "Song", hasTrack = true, status = status).stopped()
            assertEquals(status, state.status)
            assertFalse(state.pauseAction)
        }
        assertFalse(WidgetState(hasTrack = true, status = WidgetStatus.Permission).stopped().canControl)
    }

    @Test fun deniedPermissionReturnsBeforeBindingOrPlaying() = runBlocking {
        val denied = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED
        }
        assertEquals(WidgetStatus.Permission, controlFromWidget(denied, WidgetAction.Toggle).status)
    }

    @Test fun largeArtworkIsDecodedToWidgetSizeAndMissingUriFallsBack() {
        val file = context.cacheDir.resolve("widget-art-test-${System.nanoTime()}.png")
        val source = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val decoded = requireNotNull(loadArtwork(context.contentResolver, android.net.Uri.fromFile(file).toString(), 192))
            assertTrue(decoded.width <= 192 && decoded.height <= 192)
            assertTrue(decoded.byteCount <= 192 * 192 * 4)
            assertNull(loadArtwork(context.contentResolver, "content://izuna.fixture.missing/art", 192))
        } finally { source.recycle(); file.delete() }
    }
}
