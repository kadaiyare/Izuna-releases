package com.kadaiyare.izuna

import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.data.history.HistoryDatabase
import com.kadaiyare.izuna.data.history.HistoryRepository
import com.kadaiyare.izuna.data.settings.AppSettings
import com.kadaiyare.izuna.playback.PlaybackHistoryRecorder
import com.kadaiyare.izuna.playback.toMediaItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Simulated playing state, isolated history DB and invalid fixture URI: no audio or network submission. */
class HistoryClearPlaybackTest {
    @Test fun clearingWhilePlayingStartsANewZeroTimeSessionWithoutChangingThePlayer() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        val job = SupervisorJob()
        val history = HistoryRepository.create(context, database, CoroutineScope(job + Dispatchers.IO))
        val media = Track("playing-clear", "test://playing-clear", "Clear playback fixture", "Izuna Tests", "Tests", 12000, null, 1, 1).toMediaItem()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "addListener", "removeListener" -> null
                "getCurrentMediaItem" -> media
                "isPlaying" -> true
                "getPlaybackState" -> Player.STATE_READY
                "getPlayerError" -> null
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "History clear test player"
                else -> error("Unexpected Player operation: ${method.name}")
            }
        } as Player
        var recorder: PlaybackHistoryRecorder? = null
        try {
            history.awaitInitialized()
            instrumentation.runOnMainSync {
                recorder = PlaybackHistoryRecorder(context, player, history, MutableStateFlow(AppSettings(recordHistory = true)))
            }
            val old = withTimeout(10000) { history.observeRecent().first { it.isNotEmpty() } }.single()
            history.clearHistory()
            val fresh = withTimeout(10000) { history.observeRecent().first { rows -> rows.size == 1 && rows.single().id != old.id } }.single()
            assertEquals(0L, fresh.listenedMs)
            assertNull(fresh.endedAtMs)
            assertEquals(media.localConfiguration!!.uri.toString(), fresh.track.uri)
            assertTrue(player.isPlaying)
            assertSame(media, player.currentMediaItem)
        } finally {
            instrumentation.runOnMainSync { recorder?.close() }
            job.cancelAndJoin()
            database.close()
        }
    }
}
