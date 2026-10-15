package com.kadaiyare.izuna

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.ListeningSession
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.data.history.HistoryDatabase
import com.kadaiyare.izuna.data.history.entry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val track = Track("1", "content://media/external/audio/media/1", "曲名🎵", "歌手", "アルバム", 12_000, null, 1, 2,
        albumArtist = "アルバム名義")

    @Test fun upsertAndReopeningPreserveOneSessionWithUnicodeMetadata() = runBlocking {
        val name = "history-test-${System.nanoTime()}.db"
        var database = Room.databaseBuilder(context, HistoryDatabase::class.java, name).build()
        try {
            val session = ListeningSession("session", track, 1_000, 2_000, listenedMs = 1_000)
            database.history().save(session.entry())
            val finished = session.copy(updatedAtMs = 3_000, endedAtMs = 3_000, listenedMs = 2_000)
            database.history().save(finished.entry())
            database.close()
            database = Room.databaseBuilder(context, HistoryDatabase::class.java, name).build()
            assertEquals(listOf(finished), database.history().observeRecent().first().map { it.session() })
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun recentListLimitsToNewestHundredWithStableTieOrder() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        try {
            repeat(105) { index ->
                database.history().save(ListeningSession("%03d".format(index), track, (index / 2).toLong(), 1_000).entry())
            }
            val rows = database.history().observeRecent().first()
            assertEquals(100, rows.size)
            assertEquals("104", rows.first().id)
            assertEquals("005", rows.last().id)
            assertEquals(listOf("103", "102"), rows.slice(1..2).map { it.id })
        } finally { database.close() }
    }

    @Test fun interruptedSessionEndsAtLastSavedTimeWithoutChangingCompletedRows() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        try {
            val interrupted = ListeningSession("interrupted", track, 1_000, 2_000, listenedMs = 500)
            val completed = ListeningSession("completed", track, 500, 900, endedAtMs = 900, listenedMs = 400)
            database.history().save(interrupted.entry())
            database.history().save(completed.entry())
            assertNull(database.history().observeRecent().first().first().endedAtMs)
            database.history().finishInterruptedSessions()
            val rows = database.history().observeRecent().first().associateBy { it.id }
            assertEquals(interrupted.copy(endedAtMs = 2_000), rows.getValue("interrupted").session())
            assertEquals(completed, rows.getValue("completed").session())
        } finally { database.close() }
    }
}
