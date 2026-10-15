package com.kadaiyare.izuna

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.*
import com.kadaiyare.izuna.data.history.*
import com.kadaiyare.izuna.data.lastfm.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrobbleDeliveryTest {
    private val config = LastFmConfig("0".repeat(32), "2".repeat(32))
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private class Store(var auth: StoredLastFmAuth) : LastFmAuthStore {
        override fun read() = auth
        override fun write(auth: StoredLastFmAuth) { this.auth = auth }
    }
    private val unusedApi = object : LastFmAuthApi {
        override suspend fun token(): String = error("Not part of delivery tests")
        override suspend fun session(token: String): StoredLastFmAuth.Connected = error("Not part of delivery tests")
    }
    private inner class Fixture(private val databaseName: String? = null) {
        private fun openDatabase() = if (databaseName == null) Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
            else Room.databaseBuilder(context, HistoryDatabase::class.java, databaseName).build()
        var db = openDatabase()
        val dao get() = db.history()
        var time = 10_000L
        val store = Store(StoredLastFmAuth.Connected("A", "3".repeat(32), config.apiKey))
        var auth = LastFmAuthRepository(config, unusedApi, store) { time }
        val requests = mutableListOf<ScrobbleJob>()
        var outcome: ScrobbleResult = ScrobbleResult.Accepted
        val api = ScrobbleApi { job, session ->
            assertEquals(session.accountName, job.accountName)
            requests += job
            outcome
        }
        fun engine() = ScrobbleDelivery(dao, auth, api) { time }
        fun job(id: String, account: String = "A", start: Long = 11_000) =
            ScrobbleJob(id, "listen-$id", account, "タグの曲名", "タグの歌手", null, 40_000, start, time)
        suspend fun ready() { withTimeout(5000) { auth.state.first { !it.loading } } }
        suspend fun enable() { ready(); auth.setScrobbling(true) }
        suspend fun counts() = dao.observeJobCounts("A").first().associate { it.status to it.count }
        fun session(id: String, start: Long = 11_000) = ListeningSession(id,
            Track("id", "content://media/external/audio/media/123", "filename.flac", "表示用の歌手", "表示用アルバム", 40_000, null, 1, 0),
            start, start + 20_000, listenedMs = 20_000)
        suspend fun reopen() {
            auth.scope.cancel()
            auth = LastFmAuthRepository(config, unusedApi, store) { time }
            ready()
        }
        fun reopenDatabase() { require(databaseName != null); db.close(); db = openDatabase() }
        fun close() { auth.scope.cancel(); db.close(); databaseName?.let { context.deleteDatabase(it) } }
    }
    private suspend fun withFixture(databaseName: String? = null, action: suspend Fixture.() -> Unit) {
        val fixture = Fixture(databaseName)
        try { withTimeout(15_000) { fixture.ready(); fixture.action() } }
        finally { fixture.close() }
    }

    @Test fun connectionAloneDoesNotSendAndConsentSurvivesReopening() = runBlocking {
        withFixture {
            dao.insertJob(job("queued"))
            assertNull(auth.destination())
            assertEquals(ScrobbleDrain.Done, engine().drain())
            assertTrue(requests.isEmpty())
            enable()
            assertEquals(10_000L, auth.destination()!!.enabledAtMs)
            reopen()
            assertEquals(ScrobbleDrain.Done, engine().drain())
            assertEquals(1, requests.size)
            assertEquals(1, counts()["accepted"])
            assertEquals(ScrobbleDrain.Done, engine().drain())
            assertEquals(1, requests.size)
        }
    }

    @Test fun structuredTagsAreQueuedOnceAndPreEnablePlaybackRemainsLocal() = runBlocking {
        withFixture {
            enable()
            var tagReads = 0
            val writer = ScrobbleHistoryWriter(dao, auth) { tagReads++; ScrobbleMetadata("タグの曲名", "タグの歌手") }
            assertFalse(writer.save(session("old", 9000), auth.destination()))
            assertTrue(writer.save(session("new"), auth.destination()))
            assertFalse(writer.save(session("new").copy(listenedMs = 25_000), auth.destination()))
            assertEquals(1, tagReads)
            assertEquals("タグの曲名", dao.pendingJobs("A").single().title)
            assertEquals(2, dao.observeRecent().first().size)
            time = 31_000 // The real clock has reached the playback checkpoint's time.
            engine().drain()
            assertEquals("タグの歌手", requests.single().artist)
        }
    }

    @Test fun absentTagsAndTagReadFailureDoNotUseDisplayNames() = runBlocking {
        withFixture {
            enable()
            val writer = ScrobbleHistoryWriter(dao, auth) { null }
            val broken = ScrobbleHistoryWriter(dao, auth) { throw IOException("Fixture unreadable") }
            assertFalse(writer.save(session("missing"), auth.destination()))
            assertFalse(broken.save(session("broken"), auth.destination()))
            assertTrue(dao.pendingJobs("A").isEmpty())
            assertEquals(2, dao.observeRecent().first().size)
        }
    }

    @Test fun disablingDuringTagReadPreventsQueuingAndReenableSkipsEarlierPlayback() = runBlocking {
        withFixture {
            enable()
            val destination = auth.destination()
            val writer = ScrobbleHistoryWriter(dao, auth) { auth.setScrobbling(false); ScrobbleMetadata("Song", "Artist") }
            assertFalse(writer.save(session("race"), destination))
            assertTrue(dao.pendingJobs("A").isEmpty())
            time = 30_000
            auth.setScrobbling(true)
            val fresh = ScrobbleHistoryWriter(dao, auth) { ScrobbleMetadata("Song", "Artist") }
            assertFalse(fresh.save(session("older", 11_000), auth.destination()))
            assertTrue(fresh.save(session("later", 31_000), auth.destination()))
        }
    }

    @Test fun retryDefersOldestAndNeverLetsNewerScrobblesPassIt() = runBlocking {
        withFixture {
            enable()
            dao.insertJob(job("newer", start = 12_000)); dao.insertJob(job("older", start = 11_000))
            outcome = ScrobbleResult.Retry
            assertEquals(ScrobbleDrain.Retry, engine().drain())
            assertEquals("older", requests.single().id)
            assertEquals(70_000L, dao.pendingJobs("A").first().nextAttemptAtMs)
            reopen()
            outcome = ScrobbleResult.Accepted
            assertEquals(ScrobbleDrain.Retry, engine().drain())
            assertEquals(1, requests.size)
            time = 70_000
            assertEquals(ScrobbleDrain.Done, engine().drain())
            assertEquals(listOf("older", "older", "newer"), requests.map { it.id })
            assertEquals(2, counts()["accepted"])
        }
    }

    @Test fun interruptedAndAmbiguousRequestsRequireExplicitRetry() = runBlocking {
        withFixture {
            enable()
            dao.insertJob(job("interrupted")); dao.claimJob("interrupted", "A")
            dao.insertJob(job("lost-response", start = 12_000))
            outcome = ScrobbleResult.Uncertain
            assertEquals(ScrobbleDrain.Done, engine().drain())
            assertEquals(listOf("lost-response"), requests.map { it.id })
            assertEquals(2, counts()["uncertain"])
            assertEquals(ScrobbleDrain.Done, engine().drain())
            assertEquals(1, requests.size)
            dao.retryUncertain("A", time)
            outcome = ScrobbleResult.Accepted
            assertEquals(ScrobbleDrain.Done, engine().drain())
            assertEquals(2, counts()["accepted"])
        }
    }

    @Test fun ignoredAndPermanentErrorsAreTerminalInsteadOfRetriedForever() = runBlocking {
        withFixture {
            enable()
            dao.insertJob(job("ignored")); outcome = ScrobbleResult.Ignored(3); engine().drain()
            dao.insertJob(job("bad")); outcome = ScrobbleResult.Rejected(13); engine().drain()
            assertTrue(dao.pendingJobs("A").isEmpty())
            assertEquals(1, counts()["ignored:3"])
            assertEquals(1, counts()["rejected:13"])
            engine().drain(); assertEquals(2, requests.size)
        }
    }

    @Test fun invalidSessionDisablesSendingAndKeepsJobForTheSameAccount() = runBlocking {
        withFixture {
            enable(); dao.insertJob(job("reauth")); outcome = ScrobbleResult.Reauthenticate
            assertEquals(ScrobbleDrain.Done, engine().drain())
            assertNull(auth.destination())
            assertNull(auth.state.value.accountName)
            assertEquals(StoredLastFmAuth.Empty, store.auth)
            assertEquals(1, dao.pendingJobs("A").size)
            engine().drain(); assertEquals(1, requests.size)
        }
    }

    @Test fun anotherAccountCannotSendOrRequeueExistingJobs() = runBlocking {
        withFixture {
            enable(); dao.insertJob(job("A-job")); dao.claimJob("A-job", "A"); dao.recoverSending("A")
            dao.insertJob(job("B-job", "B"))
            assertEquals(ScrobbleDrain.Done, engine().drain())
            assertTrue(requests.isEmpty())
            auth.scope.cancel()
            store.auth = StoredLastFmAuth.Connected("B", "4".repeat(32), config.apiKey)
            auth = LastFmAuthRepository(config, unusedApi, store) { time }
            enable(); dao.retryUncertain("B", time); engine().drain()
            assertEquals(listOf("B-job"), requests.map { it.id })
            assertEquals(1, counts()["uncertain"])
        }
    }

    @Test fun disablingWhileARequestRunsStopsTheNextRequest() = runBlocking {
        withFixture {
            enable(); dao.insertJob(job("first")); dao.insertJob(job("second", start = 12_000))
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val blocking = ScrobbleApi { job, _ -> requests += job; started.complete(Unit); finish.await(); ScrobbleResult.Accepted }
            coroutineScope {
                val sending = async { ScrobbleDelivery(dao, auth, blocking) { time }.drain() }
                started.await()
                val disabled = async(start = CoroutineStart.UNDISPATCHED) { auth.setScrobbling(false) }
                // Wait until the disable operation has reached the auth lock on IO.
                delay(50)
                finish.complete(Unit)
                disabled.await(); sending.await()
            }
            assertEquals(listOf("first"), requests.map { it.id })
            assertEquals(1, dao.pendingJobs("A").size)
        }
    }

    @Test fun retryDeadlineAndInterruptedRequestSurviveDatabaseReopening() = runBlocking {
        withFixture("delivery-test-${java.util.UUID.randomUUID()}.db") {
            enable()
            dao.insertJob(job("interrupted")); dao.claimJob("interrupted", "A")
            dao.insertJob(job("retry", start = 12_000)); outcome = ScrobbleResult.Retry
            assertEquals(ScrobbleDrain.Retry, engine().drain())
            reopenDatabase(); reopen()
            val pending = dao.pendingJobs("A").single()
            assertEquals(70_000L, pending.nextAttemptAtMs)
            assertEquals(1, pending.attemptCount)
            assertEquals(1, counts()["uncertain"])
            assertEquals(ScrobbleDrain.Retry, engine().drain())
            assertEquals(1, requests.size)
            time = 70_000; outcome = ScrobbleResult.Accepted
            assertEquals(ScrobbleDrain.Done, engine().drain())
            assertEquals(listOf("retry", "retry"), requests.map { it.id })
        }
    }

    @Test fun backlogContinuesWithoutApplyingFailureBackoffAndStopsExactlyOnce() = runBlocking {
        withFixture {
            enable()
            repeat(13) { dao.insertJob(job("job-$it", start = 11_000L + it * 1000)) }
            assertEquals(ScrobbleDrain.More, engine().drain())
            assertEquals(10, requests.size)
            assertEquals(3, dao.pendingJobs("A").size)
            assertEquals(ScrobbleDrain.Done, engine().drain())
            assertEquals(13, requests.size)
            assertEquals(13, requests.map { it.id }.distinct().size)
            assertEquals(13, counts()["accepted"])
            assertEquals(ScrobbleDrain.Done, engine().drain())
            assertEquals(13, requests.size)
        }
    }
}
