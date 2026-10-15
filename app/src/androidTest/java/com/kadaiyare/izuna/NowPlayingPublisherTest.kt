package com.kadaiyare.izuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kadaiyare.izuna.core.model.*
import com.kadaiyare.izuna.data.lastfm.*
import com.kadaiyare.izuna.playback.NowPlayingPublisher
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingPublisherTest {
    private val config = LastFmConfig("0".repeat(32), "2".repeat(32))
    private class Store(var auth: StoredLastFmAuth) : LastFmAuthStore {
        override fun read() = auth
        override fun write(auth: StoredLastFmAuth) { this.auth = auth }
    }
    private inner class Fixture {
        var wall = 10_000L
        var elapsed = 1000L
        val store = Store(StoredLastFmAuth.Connected("A", "3".repeat(32), config.apiKey))
        val connectionApi = object : LastFmAuthApi {
            override suspend fun token() = "4".repeat(32)
            override suspend fun session(token: String) = StoredLastFmAuth.Connected("B", "5".repeat(32), config.apiKey)
        }
        val auth = LastFmAuthRepository(config, connectionApi, store) { wall }
        val requests = mutableListOf<Pair<ScrobbleMetadata, String>>()
        var reads = 0
        var tags: suspend (String) -> ScrobbleMetadata? = { uri -> ScrobbleMetadata(uri.substringAfterLast('/'), "タグの歌手") }
        var outcome = NowPlayingResult.Accepted
        var api: suspend (ScrobbleMetadata, StoredLastFmAuth.Connected) -> NowPlayingResult = { metadata, connected ->
            requests += metadata to connected.accountName; outcome
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val publisher = NowPlayingPublisher(auth, NowPlayingApi { metadata, _, connected -> api(metadata, connected) },
            scope, { uri -> reads++; tags(uri) }) { elapsed }
        fun session(id: String, start: Long = 11_000) = ListeningSession(id,
            Track(id, "content://fixture/$id", "filename.flac", "表示用歌手", "表示用アルバム", 12_000, null, 1, 0), start, start)
        suspend fun ready() { auth.state.first { !it.loading } }
        suspend fun enable() { auth.setScrobbling(true) }
        suspend fun settle() { yield(); delay(30) }
        suspend fun until(condition: () -> Boolean) { while (!condition()) delay(5) }
        fun close() { publisher.close(); auth.scope.cancel() }
    }
    private suspend fun fixture(action: suspend Fixture.() -> Unit) = withContext(Dispatchers.Main.immediate) {
        val fixture = Fixture()
        try { withTimeout(10_000) { fixture.ready(); fixture.action() } } finally { fixture.close() }
    }

    @Test fun requiresConsentAndNewPlaybackAndSendsShortTracksOnce() = runBlocking {
        fixture {
            publisher.update(session("without-consent"), true); settle()
            assertEquals(0, reads); assertTrue(requests.isEmpty())
            enable(); publisher.update(session("without-consent"), true); settle()
            assertTrue(requests.isEmpty())
            publisher.update(session("old", 9000), true); settle(); assertTrue(requests.isEmpty())
            publisher.update(session("fresh"), true); until { requests.size == 1 }
            assertEquals("fresh", requests.single().first.title)
            assertEquals("タグの歌手", requests.single().first.artist)
            repeat(5) { publisher.update(session("fresh"), true) }; settle()
            assertEquals(1, requests.size)
            publisher.update(session("fresh"), false); publisher.update(session("fresh"), true); settle()
            assertEquals(1, requests.size)
            publisher.update(session("repeat-new-session"), true); until { requests.size == 2 }
        }
    }

    @Test fun rapidSkipsDiscardOldTagsAndSendOnlyNewestTrack() = runBlocking {
        fixture {
            enable()
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            tags = { uri -> if (uri.endsWith("A")) { started.complete(Unit); finish.await() }; ScrobbleMetadata(uri.substringAfterLast('/'), "Artist") }
            publisher.update(session("A"), true); started.await()
            publisher.update(session("B"), true); publisher.update(session("C"), true)
            finish.complete(Unit); until { requests.size == 1 }
            assertEquals(listOf("C"), requests.map { it.first.title }); assertEquals(2, reads)
        }
    }

    @Test fun pauseDuringTagReadRemainsCancelledAfterResume() = runBlocking {
        fixture {
            enable()
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            tags = { started.complete(Unit); finish.await(); ScrobbleMetadata("Song", "Artist") }
            publisher.update(session("A"), true); started.await()
            publisher.update(session("A"), false); publisher.update(session("A"), true)
            finish.complete(Unit); settle()
            assertTrue(requests.isEmpty()); assertEquals(1, reads)
        }
    }

    @Test fun disableAndReenableDuringPreparationCannotReuseOldConsent() = runBlocking {
        fixture {
            enable()
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            tags = { started.complete(Unit); finish.await(); ScrobbleMetadata("Song", "Artist") }
            publisher.update(session("A"), true); started.await()
            auth.setScrobbling(false); wall = 20_000; enable()
            finish.complete(Unit); settle()
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun pendingNotificationExpiresWhileWaitingForAuthLock() = runBlocking {
        fixture {
            enable()
            val locked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val blocker = scope.launch { auth.withEnabledSession { locked.complete(Unit); release.await() } }
            locked.await(); publisher.update(session("A"), true); until { reads == 1 }
            elapsed += 30_001; release.complete(Unit); blocker.join(); settle()
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun accountChangeDuringTagReadCannotSendOldPlaybackToNewAccount() = runBlocking {
        fixture {
            enable()
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            tags = { uri ->
                if (uri.endsWith("A")) { started.complete(Unit); finish.await() }
                ScrobbleMetadata(uri.substringAfterLast('/'), "Artist")
            }
            publisher.update(session("A"), true); started.await()
            auth.disconnect(); auth.begin(); auth.confirm(); wall = 20_000; enable()
            assertEquals("B", auth.destination()!!.accountName)
            finish.complete(Unit); settle(); assertTrue(requests.isEmpty())
            publisher.update(session("new-account-song", 21_000), true); until { requests.size == 1 }
            assertEquals("new-account-song", requests.single().first.title)
            assertEquals("B", requests.single().second)
        }
    }

    @Test fun missingTagsAndReadFailuresNeverFallBackOrRetry() = runBlocking {
        fixture {
            enable(); tags = { null }
            publisher.update(session("missing"), true); settle()
            tags = { throw IOException("Fixture unreadable") }
            publisher.update(session("broken"), true); settle()
            repeat(3) { publisher.update(session("broken"), true) }; settle()
            assertEquals(2, reads); assertTrue(requests.isEmpty())
            tags = { ScrobbleMetadata("", "Artist") }
            publisher.update(session("invalid"), true); settle(); assertTrue(requests.isEmpty())
        }
    }

    @Test fun failedNotificationDoesNotRetryOnPeriodicUpdatesOrResume() = runBlocking {
        fixture {
            enable(); outcome = NowPlayingResult.Failed
            publisher.update(session("A"), true); until { requests.size == 1 }
            repeat(3) { publisher.update(session("A"), true) }
            publisher.update(session("A"), false); publisher.update(session("A"), true); settle()
            assertEquals(1, requests.size)
            outcome = NowPlayingResult.Accepted
            publisher.update(session("B"), true); until { requests.size == 2 }
        }
    }

    @Test fun invalidSessionClearsConnectionAndPreventsLaterNotifications() = runBlocking {
        fixture {
            enable(); outcome = NowPlayingResult.Reauthenticate
            publisher.update(session("A"), true); until { auth.state.value.accountName == null }
            assertEquals(StoredLastFmAuth.Empty, store.auth); assertNull(auth.destination())
            publisher.update(session("B"), true); settle(); assertEquals(1, requests.size)
        }
    }

    @Test fun stoppingHistoryOrClosingPublisherDropsPendingNotification() = runBlocking {
        fixture {
            enable()
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            tags = { started.complete(Unit); finish.await(); ScrobbleMetadata("Song", "Artist") }
            publisher.update(session("A"), true); started.await()
            publisher.update(null, false); finish.complete(Unit); settle(); assertTrue(requests.isEmpty())
            publisher.close(); publisher.update(session("B"), true); settle(); assertTrue(requests.isEmpty())
        }
    }

    @Test fun inFlightRequestCompletesBeforeNewestNotificationAndSkipsIntermediateTracks() = runBlocking {
        fixture {
            enable()
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            api = { metadata, connected ->
                requests += metadata to connected.accountName
                if (metadata.title == "A") { started.complete(Unit); finish.await() }
                NowPlayingResult.Accepted
            }
            publisher.update(session("A"), true); started.await()
            publisher.update(session("B"), true); publisher.update(session("C"), true)
            finish.complete(Unit); until { requests.size == 2 }
            assertEquals(listOf("A", "C"), requests.map { it.first.title })
        }
    }
}
