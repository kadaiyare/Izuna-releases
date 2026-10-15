package com.kadaiyare.izuna

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.data.library.MediaStoreLibrary
import com.kadaiyare.izuna.data.playback.PlaybackRepository
import com.kadaiyare.izuna.data.settings.SettingsRepository
import com.kadaiyare.izuna.playback.PlaybackService
import com.kadaiyare.izuna.playback.toMediaItem
import com.kadaiyare.izuna.widget.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private inner class Fixture {
        val settings = SettingsRepository.get(context)
        val manager = AppWidgetManager.getInstance(context)
        lateinit var host: AppWidgetHost
        val ids = mutableListOf<Int>()
        val views = mutableListOf<AppWidgetHostView>()
        val uris = mutableListOf<Uri>()
        var scenario: ActivityScenario<MainActivity>? = null
        var controller: MediaController? = null
        lateinit var tracks: List<Track>

        fun open() {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_AUDIO)
            runBlocking { settings.setRestoreQueue(false) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            repeat(2) { index ->
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "widget-fixture-${System.nanoTime()}-$index.flac")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/IzunaWidgetTests")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
                uris += uri
                context.contentResolver.openOutputStream(uri)!!.use { output ->
                    instrumentation.context.assets.open("silence.flac").use { it.copyTo(output) }
                }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
            tracks = runBlocking { MediaStoreLibrary(context.contentResolver).scan() }.filter { Uri.parse(it.uri) in uris }
                .mapIndexed { index, track -> track.copy(title = "Widget fixture $index") }
            assertEquals(2, tracks.size)
            controller = connect()
            instrumentation.runOnMainSync {
                controller!!.run { pause(); stop(); clearMediaItems(); shuffleModeEnabled = false; repeatMode = Player.REPEAT_MODE_OFF }
            }
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try {
                instrumentation.runOnMainSync {
                    host = AppWidgetHost(context, 0x495a5747)
                    host.startListening()
                    repeat(2) {
                        val id = host.allocateAppWidgetId(); ids += id
                        assertTrue("Dedicated test host must be allowed to bind", manager.bindAppWidgetIdIfAllowed(id,
                            ComponentName(context, NowPlayingWidgetProvider::class.java), Bundle().apply {
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320)
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320)
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
                            }))
                        views += host.createView(context, id, manager.getAppWidgetInfo(id))
                    }
                }
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            runBlocking { controlFromWidget(context, WidgetAction.Refresh) }
        }

        fun selectQueue() {
            instrumentation.runOnMainSync { controller!!.run { setMediaItems(tracks.map(Track::toMediaItem)); prepare() } }
            awaitPlayer { currentMediaItem?.mediaId == tracks[0].id && playbackState == Player.STATE_READY && !isPlaying }
            awaitViews { findViewById<TextView>(R.id.widget_title)?.text == tracks[0].title }
        }
        fun click(widget: Int, button: Int) {
            instrumentation.runOnMainSync { assertTrue(views[widget].findViewById<ImageButton>(button).performClick()) }
        }
        fun awaitPlayer(predicate: MediaController.() -> Boolean) = awaitCondition {
            var matches = false
            instrumentation.runOnMainSync { matches = controller!!.predicate() }
            matches
        }
        fun awaitViews(predicate: AppWidgetHostView.() -> Boolean) = awaitCondition {
            var matches = false
            instrumentation.runOnMainSync { matches = views.all { it.predicate() } }
            matches
        }
        fun close() {
            instrumentation.runOnMainSync {
                controller?.run { pause(); stop(); clearMediaItems(); release() }
                if (::host.isInitialized) { host.stopListening(); host.deleteHost() }
            }
            scenario?.close()
            runBlocking { settings.setRestoreQueue(true) }
            uris.forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test fun twoWidgetsControlAndFollowTheSameBackgroundPlayer() {
        val fixture = Fixture()
        try {
            fixture.open(); fixture.selectQueue()
            fixture.scenario!!.moveToState(Lifecycle.State.CREATED)
            fixture.click(0, R.id.widget_toggle)
            fixture.awaitPlayer { isPlaying }
            fixture.awaitViews { findViewById<TextView>(R.id.widget_status)?.text == context.getString(R.string.widget_status_playing) &&
                findViewById<ImageButton>(R.id.widget_toggle)?.contentDescription == context.getString(R.string.widget_pause) }
            fixture.click(1, R.id.widget_next)
            fixture.awaitPlayer { currentMediaItem?.mediaId == fixture.tracks[1].id && isPlaying }
            fixture.awaitViews { findViewById<TextView>(R.id.widget_title)?.text == fixture.tracks[1].title }
            fixture.click(0, R.id.widget_previous)
            fixture.awaitPlayer { currentMediaItem?.mediaId == fixture.tracks[0].id && isPlaying }
            fixture.click(1, R.id.widget_toggle)
            fixture.awaitPlayer { !playWhenReady }
            fixture.awaitViews { findViewById<ImageButton>(R.id.widget_toggle)?.contentDescription == context.getString(R.string.widget_play) }
            fixture.click(0, R.id.widget_next)
            fixture.awaitPlayer { currentMediaItem?.mediaId == fixture.tracks[1].id && !playWhenReady }
            fixture.click(1, R.id.widget_toggle)
            fixture.awaitPlayer { isPlaying }
            instrumentation.runOnMainSync { fixture.controller!!.seekTo(11_800) }
            fixture.awaitPlayer { playbackState == Player.STATE_ENDED }
            fixture.awaitViews { findViewById<TextView>(R.id.widget_status)?.text == context.getString(R.string.widget_status_ended) }
            fixture.click(0, R.id.widget_toggle)
            fixture.awaitPlayer { isPlaying && currentPosition < 2000 }

            // The old PendingIntent cannot control playback after its widget is removed.
            val obsolete = widgetControlIntent(context, fixture.ids[0], WidgetAction.Toggle)
            instrumentation.runOnMainSync { fixture.host.deleteAppWidgetId(fixture.ids[0]) }
            obsolete.send()
            instrumentation.waitForIdleSync()
            Thread.sleep(150)
            fixture.awaitPlayer { isPlaying }
        } finally { fixture.close() }
    }

    @Test fun widgetResumesSavedQueueAfterServiceRecreationWithoutOpeningApp() {
        val fixture = Fixture()
        try {
            fixture.open(); fixture.selectQueue()
            runBlocking { fixture.settings.setRestoreQueue(true) }
            instrumentation.runOnMainSync { fixture.controller!!.seekTo(2300) }
            awaitCondition { runBlocking { PlaybackRepository.get(context).load() }.positionMs == 2300L }
            lateinit var original: SessionToken
            instrumentation.runOnMainSync { original = fixture.controller!!.connectedToken!!; fixture.controller!!.release(); fixture.controller = null }
            fixture.scenario!!.close(); fixture.scenario = null
            context.stopService(Intent(context, PlaybackService::class.java))
            instrumentation.waitForIdleSync()
            Thread.sleep(250)

            // Merely refreshing restores paused, and the user click subsequently starts playback.
            val restored = runBlocking { controlFromWidget(context, WidgetAction.Refresh) }
            assertTrue("Restored widget state: $restored", restored.hasTrack); assertFalse(restored.pauseAction)
            fixture.controller = connect()
            // A fresh observer may connect before the service's asynchronous restore has reached it.
            fixture.awaitPlayer { currentMediaItem?.mediaId == fixture.tracks[0].id && playbackState == Player.STATE_READY }
            instrumentation.runOnMainSync {
                assertNotEquals(original, fixture.controller!!.connectedToken)
                // MediaController intentionally omits localConfiguration; mediaId is the stable track URI here.
                assertEquals(fixture.tracks[0].id, fixture.controller!!.currentMediaItem?.mediaId)
                assertFalse(fixture.controller!!.playWhenReady)
                assertEquals(2300L, fixture.controller!!.currentPosition)
            }
            fixture.click(0, R.id.widget_toggle)
            fixture.awaitPlayer { isPlaying && currentPosition >= 2300 }
            fixture.awaitViews { findViewById<TextView>(R.id.widget_status)?.text == context.getString(R.string.widget_status_playing) }
        } finally { fixture.close() }
    }

    private fun connect(): MediaController {
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync { future = MediaController.Builder(context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        return future.get(10, TimeUnit.SECONDS)
    }
    private fun awaitCondition(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        throw AssertionError("Expected widget/player state was not reached")
    }
}
