package com.kadaiyare.izuna

import android.app.Activity
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.data.discord.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No SDK initialization or real RPC, even when Discord is signed in. */
@RunWith(AndroidJUnit4::class)
class DiscordPresenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private class Fake : PresenceTransport {
        val creates = AtomicInteger()
        val clears = AtomicInteger()
        val destroyed = AtomicInteger()
        val titles = CopyOnWriteArrayList<String>()
        val images = CopyOnWriteArrayList<String>()
        val subtitles = CopyOnWriteArrayList<String>()
        val displays = CopyOnWriteArrayList<DiscordCompactDisplay>()
        val release = CountDownLatch(1)
        @Volatile var blockFirst = false
        @Volatile var failure = false
        @Volatile var status = 0
        override fun create(): Long { creates.incrementAndGet(); return 1 }
        override fun publish(handle: Long, title: String, artist: String, start: Long, end: Long, imageUrl: String?, compactDisplay: DiscordCompactDisplay) {
            subtitles += artist
            displays += compactDisplay
            images += imageUrl ?: ""
            titles += title
            if (blockFirst && titles.size == 1) check(release.await(5, TimeUnit.SECONDS))
            status = if (failure) 3 else 2
        }
        override fun clear(handle: Long) { clears.incrementAndGet(); status = 4 }
        override fun poll(handle: Long) = intArrayOf(status, 0, 0)
        override fun destroy(handle: Long) { destroyed.incrementAndGet() }
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun awaitCondition(block: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!block() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        assertTrue(block())
    }
    private fun song(title: String = "検証🎵") = PresenceSong(title, "架空の歌手", 45_000, 180_000, SystemClock.elapsedRealtime())
    private fun withManager(page: Long = 4096, configured: Boolean = true, installed: Boolean = true,
                            block: (DiscordPresence, Fake, AtomicInteger) -> Unit) {
        val fake = Fake()
        val prepared = AtomicInteger()
        val factoryCalls = AtomicInteger()
        lateinit var manager: DiscordPresence
        lateinit var activity: Activity
        onMain { activity = Activity(); manager = DiscordPresence(page, configured, { installed }, { prepared.incrementAndGet() }, { factoryCalls.incrementAndGet(); fake }); manager.attach(activity) }
        try { block(manager, fake, prepared) }
        finally {
            fake.release.countDown()
            onMain { manager.detach(activity); manager.close() }
            if (factoryCalls.get() != 0) awaitCondition { fake.destroyed.get() >= 1 }
        }
    }
    @Test fun unsupportedPageSizeNeverPreparesCreatesOrPublishesEvenWithSavedOptIn() = withManager(page = 65536) { manager, fake, prepared ->
        onMain { manager.setEnabled(true); manager.update(song()); manager.update(null) }
        Thread.sleep(100)
        assertFalse(manager.state.value.available)
        assertEquals(0, prepared.get()); assertEquals(0, fake.creates.get()); assertTrue(fake.titles.isEmpty())
    }
    @Test fun fourKbOptOutDoesNotLoadSdk() = withManager { manager, fake, prepared ->
        onMain { manager.update(song()) }
        Thread.sleep(100)
        assertEquals(0, prepared.get()); assertEquals(0, fake.creates.get())
    }
    @Test fun displayChangeUpdatesSameTrackAndOptOutStillSuppressesIt() = withManager { manager, fake, _ ->
        val current = song()
        onMain { manager.setEnabled(true); manager.update(current) }
        awaitCondition { manager.state.value.message.startsWith("Discordへ曲情報") }
        val text = DiscordDisplaySettings(true, DiscordTitleFormat.ARTIST_SONG, DiscordCompactDisplay.TITLE)
            .format(current.title, current.artist, "架空のアルバム")
        onMain { manager.update(current.copy(title = text.title, artist = text.subtitle, compactDisplay = DiscordCompactDisplay.TITLE)) }
        awaitCondition { fake.titles.size == 2 }
        assertEquals(text.title, fake.titles.last())
        assertEquals(text.subtitle, fake.subtitles.last())
        assertEquals(DiscordCompactDisplay.TITLE, fake.displays.last())
        assertEquals(1, fake.creates.get())
        onMain { manager.setEnabled(false); manager.update(current.copy(compactDisplay = DiscordCompactDisplay.APP_NAME)) }
        awaitCondition { fake.clears.get() == 1 }
        assertEquals(2, fake.titles.size)
    }
    @Test fun ordinarySixteenKbCanShareWithOptIn() = withManager(page = 16384) { manager, fake, prepared ->
        onMain { manager.setEnabled(true); manager.update(song()) }
        awaitCondition { manager.state.value.message.startsWith("Discordへ曲情報") }
        assertTrue(manager.state.value.available)
        assertEquals(1, prepared.get()); assertEquals(1, fake.creates.get())
    }
    @Test fun sixteenKbOptOutDoesNotLoadSdk() = withManager(page = 16384) { manager, fake, prepared ->
        onMain { manager.update(song()) }
        assertTrue(manager.state.value.available)
        assertEquals(0, prepared.get()); assertEquals(0, fake.creates.get()); assertTrue(fake.titles.isEmpty())
    }
    @Test fun missingDiscordDoesNotPrepareSdk() = withManager(installed = false) { manager, fake, prepared ->
        onMain { manager.setEnabled(true); manager.update(song()) }
        assertFalse(manager.state.value.available)
        assertEquals(0, prepared.get()); assertEquals(0, fake.creates.get())
    }
    @Test fun pauseDestroysIdleClientAndResumeCreatesANewOne() = withManager { manager, fake, _ ->
        onMain { manager.setEnabled(true); manager.update(song()) }
        awaitCondition { manager.state.value.message.startsWith("Discordへ曲情報") }
        onMain { manager.update(null) }
        awaitCondition { fake.destroyed.get() == 1 }
        assertEquals(1, fake.clears.get())
        onMain { manager.update(song("再開")) }
        awaitCondition { fake.titles.size == 2 }
        assertEquals(2, fake.creates.get())
    }
    @Test fun latestTrackWinsAndOffSuppressesPendingSuccess() = withManager { manager, fake, _ ->
        fake.blockFirst = true
        onMain { manager.setEnabled(true); manager.update(song("A")) }
        awaitCondition { fake.titles.size == 1 }
        onMain { manager.update(song("古いB")); manager.update(song("新しいC")); manager.setEnabled(false) }
        fake.release.countDown()
        awaitCondition { fake.clears.get() == 1 }
        Thread.sleep(100)
        assertEquals(listOf("A"), fake.titles.toList())
        assertTrue(manager.state.value.message.startsWith("共有はオフ"))
    }
    @Test fun rejectedUpdateClearsWithoutRetry() = withManager { manager, fake, _ ->
        fake.failure = true
        onMain { manager.setEnabled(true); manager.update(song()) }
        awaitCondition { fake.clears.get() == 1 }
        Thread.sleep(150)
        assertEquals(1, fake.titles.size)
        assertTrue(manager.state.value.message.contains("送信できません"))
    }
    @Test fun nextSongWithoutCoverDoesNotInheritPreviousArtwork() = withManager { manager, fake, _ ->
        onMain { manager.setEnabled(true); manager.update(song("A").copy(imageUrl = "https://archive.org/test.jpg")) }
        awaitCondition { fake.titles.size == 1 }
        onMain { manager.update(song("B")) }
        awaitCondition { fake.titles.size == 2 }
        assertEquals(listOf("https://archive.org/test.jpg", ""), fake.images.toList())
    }
}
