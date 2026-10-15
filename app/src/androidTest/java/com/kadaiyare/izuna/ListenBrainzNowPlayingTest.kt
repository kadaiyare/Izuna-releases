package com.kadaiyare.izuna

import com.kadaiyare.izuna.core.model.*
import com.kadaiyare.izuna.data.listenbrainz.*
import com.kadaiyare.izuna.playback.ListenBrainzNowPlayingPublisher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ListenBrainzNowPlayingTest {
    private suspend fun fixture(action: suspend Fixture.() -> Unit) = withContext(Dispatchers.Main.immediate) {
        val fixture = Fixture()
        try { withTimeout(10_000) { fixture.auth.state.first { !it.loading }; fixture.action() } }
        finally { fixture.publisher.close(); fixture.authScope.cancel() }
    }
    private class Fixture {
        val authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = object : ListenBrainzStore {
            var connection: ListenBrainzConnection? = ListenBrainzConnection("https://example.test", "synthetic-token", "account")
            override fun read() = connection
            override fun write(connection: ListenBrainzConnection?) { this.connection = connection }
        }
        val auth = ListenBrainzRepository(store, ListenBrainzClient { _, _, _, _ -> error("No validation expected") }, authScope) { 1000 }
        val requests = mutableListOf<String>()
        var elapsed = 1000L
        var tags: suspend (String) -> ScrobbleMetadata? = { ScrobbleMetadata(it.substringAfterLast('/'), "Synthetic artist") }
        val publisher = ListenBrainzNowPlayingPublisher(auth, ListenBrainzClient { _, _, _, body ->
            val json = JSONObject(body!!)
            assertEquals("playing_now", json.getString("listen_type"))
            val item = json.getJSONArray("payload").getJSONObject(0)
            assertFalse(item.has("listened_at"))
            requests += item.getJSONObject("track_metadata").getString("track_name")
            ListenBrainzResponse(200, "{\"status\":\"ok\"}")
        }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), { tags(it) }) { elapsed }
        fun session(id: String, started: Long = 2000) = ListeningSession(id,
            Track(id, "content://fixture/$id", "Filename", "Display artist", "Display album", 12_000, null, 1, 1), started, started)
        suspend fun settle() { yield(); delay(40) }
        suspend fun until(action: () -> Boolean) { while (!action()) delay(5) }
    }
    @Test fun consentIsRequiredAndEachNewSessionSharesOnceWithoutTimestamp() = runBlocking { fixture {
        publisher.update(session("off"), true); settle(); assertTrue(requests.isEmpty())
        auth.setEnabled(true); publisher.update(session("off"), true); settle(); assertTrue(requests.isEmpty())
        publisher.update(session("old", 999), true); settle(); assertTrue(requests.isEmpty())
        publisher.update(session("A"), true); until { requests.size == 1 }
        repeat(4) { publisher.update(session("A"), true) }
        publisher.update(session("A"), false); publisher.update(session("A"), true); settle()
        assertEquals(listOf("A"), requests)
        publisher.update(session("repeat"), true); until { requests.size == 2 }
    } }
    @Test fun skipDuringTagReadDropsOldSongAndOnlySendsLatest() = runBlocking { fixture {
        auth.setEnabled(true)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        tags = { if (it.endsWith("A")) { started.complete(Unit); release.await() }; ScrobbleMetadata(it.substringAfterLast('/'), "Artist") }
        publisher.update(session("A"), true); started.await()
        publisher.update(session("B"), true); publisher.update(session("C"), true)
        release.complete(Unit); until { requests.size == 1 }; assertEquals(listOf("C"), requests)
    } }
    @Test fun pauseOrHistoryOffCancelsPendingNotificationsEvenAfterResume() = runBlocking { fixture {
        auth.setEnabled(true)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        tags = { started.complete(Unit); release.await(); ScrobbleMetadata("A", "Artist") }
        publisher.update(session("A"), true); started.await()
        publisher.update(session("A"), false); publisher.update(session("A"), true)
        release.complete(Unit); settle(); assertTrue(requests.isEmpty())
        tags = { null }; publisher.update(session("missing"), true); settle(); assertTrue(requests.isEmpty())
        publisher.update(null, false)
    } }
    @Test fun disconnectWhilePreparingCannotSendStaleSong() = runBlocking { fixture {
        auth.setEnabled(true)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        tags = { started.complete(Unit); release.await(); ScrobbleMetadata("A", "Artist") }
        publisher.update(session("A"), true); started.await(); auth.disconnect()
        release.complete(Unit); settle(); assertTrue(requests.isEmpty())
    } }
    @Test fun notificationExpiresAfterThirtySecondsOfTagPreparation() = runBlocking { fixture {
        auth.setEnabled(true)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        tags = { started.complete(Unit); release.await(); ScrobbleMetadata("A", "Artist") }
        publisher.update(session("A"), true); started.await(); elapsed += 30_001
        release.complete(Unit); settle(); assertTrue(requests.isEmpty())
    } }
}
