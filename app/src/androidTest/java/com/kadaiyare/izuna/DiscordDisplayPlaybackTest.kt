package com.kadaiyare.izuna

import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.data.discord.*
import com.kadaiyare.izuna.data.settings.AppSettings
import com.kadaiyare.izuna.playback.DiscordPlaybackRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the real settings observer/tag reader without an SDK or network connection. */
class DiscordDisplayPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun awaitCondition(block: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!block() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        assertTrue(block())
    }
    private class Fake : PresenceTransport {
        val published = CopyOnWriteArrayList<Triple<String, String, DiscordCompactDisplay>>()
        @Volatile var state = 0
        @Volatile var destroyed = false
        override fun create() = 1L
        override fun publish(handle: Long, title: String, artist: String, start: Long, end: Long,
            imageUrl: String?, compactDisplay: DiscordCompactDisplay) {
            published += Triple(title, artist, compactDisplay)
            state = 2
        }
        override fun clear(handle: Long) { state = 4 }
        override fun poll(handle: Long) = intArrayOf(state, 0, 0)
        override fun destroy(handle: Long) { destroyed = true }
    }
    @Test fun currentPlaybackRefreshesOnDisplayChangesButPausedAndOptOutDoNotPublish() {
        val file = File(instrumentation.targetContext.cacheDir, "discord-display-${System.nanoTime()}.flac")
        val fake = Fake()
        val choices = MutableStateFlow(AppSettings(discordSharing = true))
        lateinit var presence: DiscordPresence
        lateinit var recorder: DiscordPlaybackRecorder
        lateinit var activity: Activity
        var playing = true
        var listener: Player.Listener? = null
        file.writeBytes(fixture())
        val item = MediaItem.fromUri(Uri.fromFile(file))
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "isPlaying" -> playing
                "getCurrentMediaItem" -> item
                "getCurrentPosition" -> 15_000L
                "getDuration" -> 180_000L
                "addListener" -> { listener = args!![0] as Player.Listener; null }
                "removeListener" -> { listener = null; null }
                else -> error("Unexpected Player call: ${method.name}")
            }
        } as Player
        onMain {
            activity = Activity()
            presence = DiscordPresence(4096, true, { true }, {}, { fake })
            presence.attach(activity)
            recorder = DiscordPlaybackRecorder(instrumentation.targetContext, player, presence, choices)
        }
        try {
            awaitCondition { fake.published.size == 1 }
            assertEquals(Triple("Fixture song", "Fixture artist", DiscordCompactDisplay.APP_NAME), fake.published.last())
            onMain { choices.value = choices.value.copy(discordDisplay = DiscordDisplaySettings(true, DiscordTitleFormat.ARTIST_SONG, DiscordCompactDisplay.TITLE)) }
            awaitCondition { fake.published.size == 2 }
            assertEquals(Triple("Fixture artist · Fixture song", "Fixture artist · Fixture album", DiscordCompactDisplay.TITLE), fake.published.last())
            onMain {
                playing = false
                listener!!.onEvents(player, Player.Events(androidx.media3.common.FlagSet.Builder().add(Player.EVENT_IS_PLAYING_CHANGED).build()))
                choices.value = choices.value.copy(discordDisplay = DiscordDisplaySettings())
            }
            awaitCondition { fake.state == 4 }
            assertEquals(2, fake.published.size)
            onMain {
                choices.value = choices.value.copy(discordSharing = false)
            }
            awaitCondition { fake.destroyed }
            onMain {
                playing = true
                listener!!.onEvents(player, Player.Events(androidx.media3.common.FlagSet.Builder().add(Player.EVENT_IS_PLAYING_CHANGED).build()))
                choices.value = choices.value.copy(discordDisplay = DiscordDisplaySettings(showAlbum = true))
            }
            Thread.sleep(100)
            assertEquals(2, fake.published.size)
        } finally {
            onMain { recorder.close(); presence.detach(activity); presence.close() }
            file.delete()
        }
    }
    private fun fixture(): ByteArray {
        val comments = ByteArrayOutputStream()
        fun little(value: Int) { comments.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        little(0); little(3)
        listOf("TITLE=Fixture song", "ARTIST=Fixture artist", "ALBUM=Fixture album").forEach {
            val bytes = it.toByteArray(Charsets.UTF_8); little(bytes.size); comments.write(bytes)
        }
        val result = ByteArrayOutputStream()
        result.write("fLaC".toByteArray()); result.write(byteArrayOf(0, 0, 0, 34)); result.write(ByteArray(34))
        val body = comments.toByteArray()
        result.write(132); result.write(body.size shr 16); result.write(body.size shr 8); result.write(body.size); result.write(body)
        return result.toByteArray()
    }
}
