package com.kadaiyare.izuna.discordprobe

import android.app.Application
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Uses no SDK initialization or RPC transport, even on a signed-in device. */
@RunWith(AndroidJUnit4::class)
class DemoPresenceLifecycleTest {
    private data class Packet(val title: String, val listening: Boolean, val music: Boolean, val start: Long, val end: Long)
    private class FakeTransport : ProbeTransport {
        val packets = CopyOnWriteArrayList<Packet>()
        val clears = AtomicInteger()
        val destroyed = AtomicInteger()
        val polls = AtomicInteger()
        val releaseFirst = CountDownLatch(1)
        @Volatile var blockFirst = false
        @Volatile var throwOnPublish = false
        @Volatile var reply = 2
        @Volatile var status = 0
        override fun create(applicationId: Long) = 1L
        override fun version() = "fake"
        override fun publish(handle: Long, title: ByteArray, artist: ByteArray, listening: Boolean, musicDetails: Boolean, startMs: Long, endMs: Long) {
            packets += Packet(title.toString(Charsets.UTF_8), listening, musicDetails, startMs, endMs)
            if (blockFirst && packets.size == 1) check(releaseFirst.await(5, TimeUnit.SECONDS))
            if (throwOnPublish) throw IllegalStateException("injected failure")
            status = reply
        }
        override fun clear(handle: Long) { clears.incrementAndGet(); status = 4 }
        override fun poll(handle: Long): IntArray {
            polls.incrementAndGet()
            return intArrayOf(status, if (status == 3) 1 else 0, 0)
        }
        override fun destroy(handle: Long) { destroyed.incrementAndGet() }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        assertTrue("Condition did not complete", condition())
    }

    private fun withModel(block: (ActivityScenario<NativeTestActivity>, ProbeViewModel, FakeTransport, AtomicLong) -> Unit) {
        assumeTrue(Os.sysconf(OsConstants._SC_PAGESIZE) == 4096L || BuildConfig.DIAGNOSE_16KB)
        val transport = FakeTransport()
        val clock = AtomicLong(1000)
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val model = ProbeViewModel(application, transport, elapsed = clock::get)
        val scenario = ActivityScenario.launch(NativeTestActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
                }
                assertSame(model, ViewModelProvider.create(activity, factory)[ProbeViewModel::class.java])
                model.initialize(activity)
            }
            awaitCondition { model.state.value.ready }
            block(scenario, model, transport, clock)
        } finally {
            transport.releaseFirst.countDown()
            scenario.close()
            awaitCondition { transport.destroyed.get() == 1 }
        }
    }

    @Test fun pausedSeekDoesNotShareAndResumeUsesFrozenPosition() = withModel { scenario, model, transport, clock ->
        scenario.onActivity { model.publish(false, true, true) }
        awaitCondition { transport.packets.size == 1 && !model.state.value.busy }
        clock.set(6000)
        scenario.onActivity { model.pause() }
        awaitCondition { transport.clears.get() == 1 }
        assertEquals(50_000, model.state.value.demo!!.positionMs)
        clock.set(60_000)
        scenario.onActivity { model.seek(90_000) }
        assertEquals(1, transport.packets.size)
        assertFalse(model.state.value.demo!!.isPlaying)
        scenario.onActivity { model.resume() }
        awaitCondition { transport.packets.size == 2 && !model.state.value.busy }
        val packet = transport.packets.last()
        assertEquals(180_000, packet.end - packet.start)
        assertTrue(packet.end - System.currentTimeMillis() in 85_000..90_000)
    }

    @Test fun onlyLatestQueuedTrackSurvivesBlockedNativeWorker() = withModel { scenario, model, transport, _ ->
        transport.blockFirst = true
        scenario.onActivity { model.publish(false, false, false) }
        awaitCondition { transport.packets.size == 1 }
        scenario.onActivity { model.pause(); model.resume(); model.seek(90_000); model.publish(true, true, true) }
        transport.releaseFirst.countDown()
        awaitCondition { transport.packets.size == 2 && !model.state.value.busy }
        assertTrue(transport.packets.last().title.contains("Demo B"))
        assertTrue(transport.packets.last().listening)
        assertTrue(transport.packets.last().music)
        assertEquals(0, transport.clears.get()) // Superseded pause, resume and seek were never submitted.
    }

    @Test fun staleSuccessAfterPauseDoesNotRestoreSharingStatus() = withModel { scenario, model, transport, _ ->
        transport.reply = 1
        scenario.onActivity { model.publish(false, true, true) }
        awaitCondition { transport.packets.size == 1 }
        scenario.onActivity { model.pause() }
        awaitCondition { model.state.value.message.startsWith("一時停止: 消去を要求しました") }
        transport.status = 2 // Simulate an obsolete callback, independent of the native generation guard.
        Thread.sleep(200)
        assertTrue(model.state.value.message.startsWith("一時停止: "))
        assertFalse(model.state.value.demo!!.isPlaying)
        assertFalse(model.state.value.busy)
    }

    @Test fun endClearsOnceWithoutAnyNewPublish() = withModel { scenario, model, transport, clock ->
        scenario.onActivity { model.publish(false, true, true) }
        awaitCondition { transport.packets.size == 1 }
        scenario.onActivity { model.seek(177_000) }
        awaitCondition { transport.packets.size == 2 }
        clock.set(4000)
        awaitCondition { model.state.value.demo == null && transport.clears.get() == 1 }
        Thread.sleep(200)
        assertEquals(1, transport.clears.get())
        assertEquals(2, transport.packets.size)
        assertTrue(model.state.value.message.startsWith("曲の終端: "))
    }

    @Test fun failedUpdateClearsOldPresenceWithoutRetry() = withModel { scenario, model, transport, _ ->
        transport.reply = 3
        scenario.onActivity { model.publish(false, true, true) }
        awaitCondition { model.state.value.demo == null && transport.clears.get() == 1 }
        Thread.sleep(200)
        assertEquals(1, transport.packets.size)
        assertTrue(model.state.value.ready)
        assertFalse(model.state.value.busy)
        assertTrue(model.state.value.message.contains("更新に失敗しました"))
    }

    @Test fun timeoutClearsAndDoesNotRetry() = withModel { scenario, model, transport, clock ->
        transport.reply = 1
        scenario.onActivity { model.publish(false, true, true) }
        awaitCondition { transport.packets.size == 1 }
        clock.set(16_001)
        awaitCondition { model.state.value.demo == null && transport.clears.get() == 1 }
        Thread.sleep(200)
        assertEquals(1, transport.packets.size)
        assertTrue(model.state.value.message.startsWith("15秒以内"))
    }

    @Test fun nativeExceptionStopsDemoAndAttemptsCleanup() = withModel { scenario, model, transport, _ ->
        transport.throwOnPublish = true
        scenario.onActivity { model.publish(false, true, true) }
        awaitCondition { !model.state.value.ready && transport.clears.get() == 1 }
        assertNull(model.state.value.demo)
        assertFalse(model.state.value.busy)
        assertEquals(1, transport.packets.size)
        Thread.sleep(100)
        val pollsAfterFailure = transport.polls.get()
        Thread.sleep(150)
        assertEquals("Fatal SDK failure must stop callback pumping", pollsAfterFailure, transport.polls.get())
    }
}
