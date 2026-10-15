package com.kadaiyare.izuna

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.ListeningSession
import com.kadaiyare.izuna.core.model.ScrobbleDestination
import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.data.history.HistoryDatabase
import com.kadaiyare.izuna.data.history.entry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrobbleStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val track = Track("1", "content://media/external/audio/media/1", "表示用の曲名", "表示用の歌手", "アルバム", 60_000, null, 1, 1)
    private val session = ListeningSession("session", track, 10_000, 40_000, listenedMs = 30_000)
    private val destination = ScrobbleDestination("test-account", 1_000)
    private val tags = ScrobbleMetadata("タグの曲名 🎵", "タグの歌手", "タグのアルバム")

    @Test fun qualifiedHistoryWithoutConnectionOrTagsDoesNotCreateUploadJobs() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        try {
            val dao = database.history()
            dao.saveWithScrobble(session.entry())
            assertTrue(dao.observeRecent().first().single().qualifiedForScrobble)
            assertEquals(0, dao.pendingJobs(destination.accountName).size)
            dao.saveWithScrobble(session.entry(), destination)
            dao.saveWithScrobble(session.entry(), destination, tags.copy(artist = "アーティスト不明"))
            assertEquals(0, dao.pendingJobs(destination.accountName).size)
            dao.saveWithScrobble(session.copy(listenedMs = 29_999).entry(), destination, tags)
            assertFalse(dao.observeRecent().first().single().qualifiedForScrobble)
            assertEquals(0, dao.pendingJobs(destination.accountName).size)
        } finally { database.close() }
    }

    @Test fun repeatedCheckpointsCreateOneJobWithTheOriginalTagSnapshot() = runBlocking {
        val name = "scrobble-test-${System.nanoTime()}.db"
        var database = Room.databaseBuilder(context, HistoryDatabase::class.java, name).build()
        try {
            database.history().saveWithScrobble(session.entry(), destination, tags)
            val first = database.history().pendingJobs(destination.accountName).single()
            database.history().saveWithScrobble(session.copy(updatedAtMs = 45_000, listenedMs = 35_000).entry(), destination, tags.copy(title = "更新したタグ"))
            database.close()
            database = Room.databaseBuilder(context, HistoryDatabase::class.java, name).build()
            assertEquals(listOf(first), database.history().pendingJobs(destination.accountName))
            assertEquals(tags.title, first.title)
            assertEquals(session.startedAtMs, first.startedAtMs)
            assertEquals("pending", first.status)
            assertEquals(0, first.attemptCount)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun jobsStayBoundToTheirAccountAndDoNotBackfillOlderHistory() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        try {
            val dao = database.history()
            dao.saveWithScrobble(session.entry(), destination, tags)
            dao.saveWithScrobble(session.entry(), destination.copy(accountName = "another-account"), tags)
            dao.saveWithScrobble(session.entry(), destination.copy(accountName = "newly-connected", enabledAtMs = 10_001), tags)
            assertEquals(1, dao.pendingJobs(destination.accountName).size)
            assertEquals(1, dao.pendingJobs("another-account").size)
            assertEquals(0, dao.pendingJobs("newly-connected").size)
        } finally { database.close() }
    }

    @Test fun pendingBatchesStartWithOldestPlayAndLimitToFifty() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        try {
            val dao = database.history()
            repeat(55) { index ->
                dao.saveWithScrobble(session.copy(id = "session-$index", startedAtMs = 10_000L + index).entry(), destination, tags)
            }
            val jobs = dao.pendingJobs(destination.accountName)
            assertEquals(50, jobs.size)
            assertEquals((0..49).map { "session-$it" }, jobs.map { it.sessionId })
        } finally { database.close() }
    }

    @Test fun failedJobInsertRollsBackItsHistoryUpdate() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        try {
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_scrobble_insert BEFORE INSERT ON scrobble_jobs BEGIN SELECT RAISE(ABORT, 'test insert failure'); END")
            val failed = runCatching { database.history().saveWithScrobble(session.entry(), destination, tags) }.isFailure
            assertTrue(failed)
            assertTrue(database.history().observeRecent().first().isEmpty())
            assertTrue(database.history().pendingJobs(destination.accountName).isEmpty())
        } finally { database.close() }
    }

    @Test fun versionOneHistoryMigratesWithoutLossOrAutomaticallyQueuedUploads() = checkHistoryMigration(1)

    @Test fun versionTwoHistoryGainsAlbumIdentityWithoutLosingRecordsOrJobs() = checkHistoryMigration(2)
    @Test fun versionThreeGainsAlbumArtistWithoutLosingHistoryOrPendingJobs() = checkHistoryMigration(3)

    private fun checkHistoryMigration(version: Int) = runBlocking {
        val name = "history-migration-test-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = instrumentation.context.assets.open("com.kadaiyare.izuna.data.history.HistoryDatabase/$version.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            for ((id, duration, listened) in listOf(Triple("qualified", 60_000L, 30_000L), Triple("short", 30_000L, 30_000L),
                Triple("not-yet", 60_000L, 29_999L), Triple("odd", 30_001L, 15_000L), Triple("odd-qualified", 30_001L, 15_001L),
                Triple("four-minutes", Long.MAX_VALUE, 240_000L), Triple("under-four", 600_000L, 239_999L))) {
                old.insertOrThrow("listening_sessions", null, ContentValues().apply {
                    put("id", id); put("startedAtMs", 10_000L); put("updatedAtMs", 40_000L); putNull("endedAtMs"); put("listenedMs", listened)
                    put("track_id", track.id); put("track_uri", track.uri); put("track_title", track.title); put("track_artist", track.artist)
                    put("track_album", track.album); put("track_durationMs", duration); putNull("track_artworkUri"); put("track_trackNumber", 1); put("track_discNumber", 1)
                    if (version >= 2) put("qualifiedForScrobble", com.kadaiyare.izuna.core.model.ScrobblePolicy.qualifies(duration, listened))
                })
            }
            if (version == 3) old.insertOrThrow("scrobble_jobs", null, ContentValues().apply {
                put("id", "old-job"); put("sessionId", "qualified"); put("provider", "lastfm")
                put("accountName", destination.accountName); put("title", tags.title); put("artist", tags.artist)
                put("album", tags.album); put("durationMs", 60_000L); put("startedAtMs", 10_000L); put("queuedAtMs", 40_000L)
                put("status", "pending"); put("attemptCount", 0); put("nextAttemptAtMs", 40_000L)
            })
            old.version = version
        }
        val database = Room.databaseBuilder(context, HistoryDatabase::class.java, name).build()
        try {
            val rows = database.history().observeRecent().first().associateBy { it.id }
            assertEquals(7, rows.size)
            assertTrue(rows.getValue("qualified").qualifiedForScrobble)
            assertFalse(rows.getValue("short").qualifiedForScrobble)
            assertFalse(rows.getValue("not-yet").qualifiedForScrobble)
            assertFalse(rows.getValue("odd").qualifiedForScrobble)
            assertTrue(rows.getValue("odd-qualified").qualifiedForScrobble)
            assertTrue(rows.getValue("four-minutes").qualifiedForScrobble)
            assertFalse(rows.getValue("under-four").qualifiedForScrobble)
            assertEquals(track.title, rows.getValue("qualified").track.title)
            assertEquals(30_000L, rows.getValue("qualified").listenedMs)
            assertTrue(rows.values.all { it.track.albumId == null })
            assertTrue(rows.values.all { it.track.albumArtist == null })
            val jobs = database.history().pendingJobs(destination.accountName)
            assertEquals(if (version == 3) 1 else 0, jobs.size)
            if (version == 3) {
                assertEquals("old-job", jobs.single().id)
                assertEquals(tags.artist, jobs.single().artist)
                assertEquals(0, jobs.single().attemptCount)
            }
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
