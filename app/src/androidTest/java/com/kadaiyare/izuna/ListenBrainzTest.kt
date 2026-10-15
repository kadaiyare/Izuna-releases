package com.kadaiyare.izuna

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.*
import com.kadaiyare.izuna.data.listenbrainz.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore

class ListenBrainzTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = "https://example.test/proxy"
    private val token = "synthetic-test-token"
    private val metadata = ScrobbleMetadata("架空の曲", "架空の歌手", "架空のアルバム")
    private val track = Track("1", "content://media/external/audio/media/1", "表示用", "表示用", "表示用", 60_000, null, 1, 1)
    private val session = ListeningSession("session", track, 10_000, 40_000, listenedMs = 30_000)
    private class MemoryStore(var saved: ListenBrainzConnection? = null) : ListenBrainzStore {
        override fun read() = saved
        override fun write(connection: ListenBrainzConnection?) { saved = connection }
    }
    private fun client(response: ListenBrainzResponse) = ListenBrainzClient { _, _, _, _ -> response }
    private suspend fun repository(scope: CoroutineScope, store: MemoryStore = MemoryStore()) =
        ListenBrainzRepository(store, client(ListenBrainzResponse(200, "{\"valid\":true,\"user_name\":\"test-account\"}")), scope) { 1000 }.also {
            it.state.first { state -> !state.loading }
            it.connect(root, token); it.setEnabled(true)
        }

    @Test fun validationAndPayloadUseCompatibleApiWithoutDeviceIdentifiers() = runBlocking {
        var calls = 0
        val api = ListenBrainzClient { endpoint, secret, path, body ->
            assertEquals(root, endpoint); assertEquals(token, secret)
            if (calls++ == 0) {
                assertEquals("/1/validate-token", path); assertNull(body)
                ListenBrainzResponse(200, "{\"valid\":true,\"user_name\":\"test-account\"}")
            } else {
                assertEquals("/1/submit-listens", path)
                val json = JSONObject(body!!)
                val item = json.getJSONArray("payload").getJSONObject(0)
                assertEquals(if (calls == 2) "single" else "playing_now", json.getString("listen_type"))
                assertEquals(calls == 2, item.has("listened_at"))
                if (calls == 2) assertEquals(10L, item.getLong("listened_at"))
                val tags = item.getJSONObject("track_metadata")
                assertEquals(metadata.title, tags.getString("track_name"))
                assertEquals(metadata.artist, tags.getString("artist_name"))
                assertEquals(metadata.album, tags.getString("release_name"))
                assertEquals(60_000L, tags.getJSONObject("additional_info").getLong("duration_ms"))
                assertFalse(body.contains("content://")); assertFalse(body.contains(token))
                ListenBrainzResponse(200, "{\"status\":\"ok\"}")
            }
        }
        assertEquals("test-account", api.validate(root, token))
        val connection = ListenBrainzConnection(root, token, "test-account")
        assertEquals(ListenBrainzResult.Accepted, api.submit(connection, metadata, 60_000, 10_000))
        assertEquals(ListenBrainzResult.Accepted, api.submit(connection, metadata, 60_000))
    }

    @Test fun rejectedTokensCannotBecomeConnections() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = MemoryStore()
            val repo = ListenBrainzRepository(store, client(ListenBrainzResponse(200, "{\"valid\":false}")), scope)
            repo.state.first { !it.loading }; repo.connect(root, token)
            assertNull(repo.state.value.account); assertNull(store.saved); assertNull(repo.destination())
            assertNotNull(repo.state.value.message)
        } finally { scope.cancel() }
    }

    @Test fun responseClassificationDoesNotAutomaticallyRepeatAmbiguousWrites() = runBlocking {
        val connection = ListenBrainzConnection(root, token, "account")
        suspend fun result(status: Int, body: String = "") = client(ListenBrainzResponse(status, body, 120))
            .submit(connection, metadata, 60_000, 10_000)
        assertEquals(ListenBrainzResult.Unauthorized, result(401))
        assertEquals(ListenBrainzResult.Unauthorized, result(403))
        assertEquals(ListenBrainzResult.Rejected, result(400))
        assertEquals(ListenBrainzResult.Uncertain, result(302))
        assertEquals(ListenBrainzResult.Uncertain, result(500))
        assertEquals(ListenBrainzResult.Uncertain, result(200, "not json"))
        assertEquals(ListenBrainzResult.Retry(120), result(429))
        for (sent in listOf(false, true)) {
            val api = ListenBrainzClient { _, _, _, _ -> throw ListenBrainzNetworkFailure(sent) }
            assertEquals(if (sent) ListenBrainzResult.Uncertain else ListenBrainzResult.Retry(), api.submit(connection, metadata, 60_000, 10_000))
        }
    }

    @Test fun tokenStorageIsEncryptedAndTamperingFailsClosed() {
        val file = File(context.noBackupFilesDir, "listenbrainz-test-${System.nanoTime()}")
        val alias = "izuna.test.listenbrainz.${System.nanoTime()}"
        try {
            val store = EncryptedListenBrainzStore(file, alias)
            assertNull(store.read())
            store.write(ListenBrainzConnection(root, token, "account", 1234))
            val restored = store.read()!!
            assertEquals(root, restored.root); assertEquals(token, restored.token); assertEquals(1234L, restored.enabledAtMs)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(token))
            assertFalse(restored.toString().contains(token))
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertTrue(runCatching { store.read() }.isFailure)
            store.write(null); assertFalse(file.exists())
        } finally {
            file.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun connectionDefaultsOffAndQueueIdentityIncludesServerAndAccount() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = repository(scope)
            val first = repo.destination()!!
            repo.disconnect(); assertNull(repo.destination())
            repo.connect(root + "/1/", token)
            assertEquals(0L, repo.state.value.enabledAtMs); assertNull(repo.destination())
            repo.setEnabled(true); assertEquals(first.id, repo.destination()!!.id)
            repo.disconnect(); repo.connect("https://another.test", token); repo.setEnabled(true)
            assertNotEquals(first.id, repo.destination()!!.id)
            assertNotEquals(ListenBrainzConnection(root, token, "account-a").id, ListenBrainzConnection(root, token, "account-b").id)
        } finally { scope.cancel() }
    }

    @Test fun thresholdMissingTagsAndOldSessionsNeverQueueAndCheckpointsDeduplicate() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ListenBrainzDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = repository(scope); val destination = repo.destination()!!
            val writer = ListenBrainzHistoryWriter(db.jobs(), repo) { metadata }
            assertFalse(writer.save(session.copy(listenedMs = 29_999), destination))
            assertFalse(writer.save(session.copy(startedAtMs = 999), destination))
            assertFalse(writer.save(session, null))
            assertFalse(ListenBrainzHistoryWriter(db.jobs(), repo) { null }.save(session, destination))
            assertTrue(writer.save(session, destination)); assertFalse(writer.save(session.copy(listenedMs = 50_000), destination))
            assertEquals(metadata.title, db.jobs().next(destination.id)!!.title)
            repo.setEnabled(false); assertFalse(writer.save(session.copy(id = "off"), destination))
        } finally { db.close(); scope.cancel() }
    }

    @Test fun queuedTagsRemainOnDiskAndCannotMoveToAnotherDestination() = runBlocking {
        val name = "listenbrainz-test-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, ListenBrainzDatabase::class.java, name).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = repository(scope); val destination = repo.destination()!!
            ListenBrainzHistoryWriter(db.jobs(), repo) { metadata }.save(session, destination)
            db.close(); db = Room.databaseBuilder(context, ListenBrainzDatabase::class.java, name).build()
            assertEquals(metadata, db.jobs().next(destination.id)!!.metadata())
            repo.disconnect(); repo.connect("https://another.test", token); repo.setEnabled(true)
            var posted = false
            val delivery = ListenBrainzDelivery(db.jobs(), repo, ListenBrainzClient { _, _, _, _ -> posted = true; error("Wrong destination") })
            assertEquals(ListenBrainzDrain.Done, delivery.drain()); assertFalse(posted)
            assertNotNull(db.jobs().next(destination.id))
        } finally { db.close(); context.deleteDatabase(name); scope.cancel() }
    }

    @Test fun acceptedDeliveryClaimsOnceAndRetainsUncertainResultsForManualRetry() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ListenBrainzDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = repository(scope); val destination = repo.destination()!!
            val writer = ListenBrainzHistoryWriter(db.jobs(), repo) { metadata }
            writer.save(session, destination)
            val first = db.jobs().next(destination.id)!!
            db.jobs().claim(first.id, destination.id) // Simulate process death after starting an upload
            assertEquals(ListenBrainzDrain.Done, ListenBrainzDelivery(db.jobs(), repo, client(ListenBrainzResponse(200, "{\"status\":\"ok\"}"))).drain())
            assertEquals(1, db.jobs().counts(destination.id).first().single { it.status == "uncertain" }.count)
            db.jobs().retry(destination.id, "uncertain", 0)
            val delivery = ListenBrainzDelivery(db.jobs(), repo, client(ListenBrainzResponse(200, "{\"status\":\"ok\"}")))
            assertEquals(ListenBrainzDrain.Done, delivery.drain())
            assertEquals(ListenBrainzDrain.Done, delivery.drain())
            assertEquals(1, db.jobs().counts(destination.id).first().single { it.status == "accepted" }.count)
        } finally { db.close(); scope.cancel() }
    }

    @Test fun rateLimitingDelaysQueueAndInvalidAuthenticationDisablesSending() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ListenBrainzDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = repository(scope); val destination = repo.destination()!!
            ListenBrainzHistoryWriter(db.jobs(), repo) { metadata }.save(session, destination)
            val delivery = ListenBrainzDelivery(db.jobs(), repo, client(ListenBrainzResponse(429, "", 120))) { 50_000 }
            assertEquals(ListenBrainzDrain.Retry, delivery.drain())
            assertEquals(170_000L, db.jobs().next(destination.id)!!.nextAttemptAtMs)
            db.jobs().retry(destination.id, "pending", 0)
            assertEquals(ListenBrainzDrain.Done, ListenBrainzDelivery(db.jobs(), repo, client(ListenBrainzResponse(401, ""))).drain())
            assertNull(repo.destination()); assertNotNull(db.jobs().next(destination.id))
            assertTrue(repo.state.value.message!!.contains("トークン"))
        } finally { db.close(); scope.cancel() }
    }
}
