package com.kadaiyare.izuna

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.ListeningSession
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.data.history.HistoryDatabase
import com.kadaiyare.izuna.data.history.HistoryRepository
import com.kadaiyare.izuna.data.history.ScrobbleJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Isolated database and no schedulers; never erases the app's history or sends scrobbles. */
class HistoryClearTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val track = Track("clear", "test://clear", "History clear fixture", "Izuna Tests", "Tests", 12000, null, 1, 1)

    @Test fun orderedClearErasesAllRowsPreservesJobsAndRejectsLateOldGenerationWrites() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        val job = SupervisorJob()
        val history = HistoryRepository.create(context, database, CoroutineScope(job + Dispatchers.IO))
        try {
            history.awaitInitialized()
            val oldGeneration = history.generation.value
            repeat(135) { index -> history.save(ListeningSession("old-$index", track, index.toLong(), 1000)) }
            withTimeout(10000) { history.observeRecent().first { rows -> rows.any { it.id == "old-134" } } }
            val pending = ScrobbleJob("pending-fixture", "old-134", "test-account", track.title, track.artist,
                track.album, track.durationMs, 1000, 2000)
            database.history().insertJob(pending)
            history.clearHistory()
            assertEquals(oldGeneration + 1, history.generation.value)
            assertTrue(history.observeRecent().first().isEmpty())
            assertEquals(listOf(pending), database.history().pendingJobs("test-account"))
            history.save(ListeningSession("old-134", track, 134, 3000, listenedMs = 2000), oldGeneration)
            val fresh = ListeningSession("after-clear", track, 4000, 4000)
            history.save(fresh)
            val rows = withTimeout(10000) { history.observeRecent().first { it.any { row -> row.id == fresh.id } } }
            assertEquals(listOf(fresh), rows)
        } finally { job.cancelAndJoin(); database.close() }
    }

    @Test fun failedClearDoesNotAdvanceTheRecordingGeneration() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        val job = SupervisorJob()
        val history = HistoryRepository.create(context, database, CoroutineScope(job + Dispatchers.IO))
        try {
            history.awaitInitialized()
            val generation = history.generation.value
            database.close()
            assertTrue(withTimeout(10000) { runCatching { history.clearHistory() }.isFailure })
            assertEquals(generation, history.generation.value)
        } finally { job.cancelAndJoin(); database.close() }
    }
}
