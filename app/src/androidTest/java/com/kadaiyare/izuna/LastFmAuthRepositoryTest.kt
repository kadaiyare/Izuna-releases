package com.kadaiyare.izuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kadaiyare.izuna.data.lastfm.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LastFmAuthRepositoryTest {
    private val config = LastFmConfig("0".repeat(32), "2".repeat(32))
    private val token = "1".repeat(32)
    private val connected = StoredLastFmAuth.Connected("test-user", "3".repeat(32), config.apiKey)

    private class MemoryStore(var value: StoredLastFmAuth = StoredLastFmAuth.Empty) : LastFmAuthStore {
        var failRead = false
        var failWrite = false
        override fun read(): StoredLastFmAuth {
            if (failRead) throw IOException("Fixture failure")
            return value
        }
        override fun write(auth: StoredLastFmAuth) {
            if (failWrite) throw IOException("Fixture failure")
            value = auth
        }
    }

    private inner class Api : LastFmAuthApi {
        var tokenCalls = 0
        var sessionCalls = 0
        var failure: Exception? = null
        var sessionGate: CompletableDeferred<Unit>? = null
        val sessionStarted = CompletableDeferred<Unit>()
        override suspend fun token(): String { tokenCalls++; return token }
        override suspend fun session(token: String): StoredLastFmAuth.Connected {
            sessionCalls++
            sessionStarted.complete(Unit)
            sessionGate?.await()
            failure?.let { throw it }
            return connected
        }
    }

    private suspend fun withRepository(
        store: MemoryStore = MemoryStore(), api: Api = Api(), config: LastFmConfig = this.config,
        now: () -> Long = { 10_000 }, block: suspend (LastFmAuthRepository) -> Unit,
    ) {
        val repository = LastFmAuthRepository(config, api, store, now)
        try {
            withTimeout(10_000) {
                repository.state.first { !it.loading }
                block(repository)
            }
        } finally { repository.scope.cancel() }
    }

    @Test fun browserTokenIsPersistedReusedAndRecoveredAfterReopening() = runBlocking {
        val store = MemoryStore()
        val api = Api()
        withRepository(store, api) { repository ->
            val url = repository.begin()
            assertTrue(url!!.startsWith("https://www.last.fm/api/auth/?"))
            assertTrue(repository.state.value.pending)
            assertEquals(url, repository.begin())
            assertEquals(1, api.tokenCalls)
        }
        withRepository(store, api) { repository ->
            assertTrue(repository.state.value.pending)
            repository.begin()
            assertEquals(1, api.tokenCalls)
        }
    }

    @Test fun unauthorizedTokenCanBeConfirmedLaterAndConnectedAccountSurvivesReopening() = runBlocking {
        val store = MemoryStore()
        val api = Api().apply { failure = LastFmApiException(14) }
        withRepository(store, api) { repository ->
            repository.begin()
            repository.confirm()
            assertTrue(repository.state.value.pending)
            assertNull(repository.state.value.accountName)
            assertTrue(repository.state.value.message!!.contains("まだ許可"))
            api.failure = null
            repository.confirm()
            assertFalse(repository.state.value.pending)
            assertEquals("test-user", repository.state.value.accountName)
            assertTrue(store.value is StoredLastFmAuth.Connected)
        }
        withRepository(store, api) { repository ->
            assertEquals("test-user", repository.state.value.accountName)
            assertNull(repository.begin())
            assertEquals(2, api.sessionCalls)
            repository.disconnect()
            assertNull(repository.state.value.accountName)
            assertEquals(StoredLastFmAuth.Empty, store.value)
        }
    }

    @Test fun expiredPendingTokenIsClearedBeforeContactingLastFm() = runBlocking {
        var time = 10_000L
        val api = Api()
        val store = MemoryStore()
        withRepository(store, api, now = { time }) { repository ->
            repository.begin()
            time += 3_600_000
            repository.confirm()
            assertFalse(repository.state.value.pending)
            assertEquals(0, api.sessionCalls)
            assertEquals(StoredLastFmAuth.Empty, store.value)
            repository.begin()
            assertEquals(2, api.tokenCalls)
        }
    }

    @Test fun invalidOrExpiredApiTokensRequireNewAuthorization() = runBlocking {
        for (code in listOf(4, 15)) {
            val store = MemoryStore()
            val api = Api().apply { failure = LastFmApiException(code) }
            withRepository(store, api) { repository ->
                repository.begin()
                repository.confirm()
                assertFalse(repository.state.value.pending)
                assertEquals(StoredLastFmAuth.Empty, store.value)
            }
        }
    }

    @Test fun offlineFailureRetainsPendingAuthorizationAndDoesNotConnect() = runBlocking {
        val api = Api().apply { failure = IOException("Fixture offline") }
        withRepository(api = api) { repository ->
            repository.begin()
            repository.confirm()
            assertTrue(repository.state.value.pending)
            assertNull(repository.state.value.accountName)
            assertFalse(repository.state.value.busy)
            api.failure = null
            repository.confirm()
            assertEquals("test-user", repository.state.value.accountName)
        }
    }

    @Test fun consumedTokenCannotBeReusedAfterSessionStorageFailure() = runBlocking {
        val store = MemoryStore()
        val api = Api()
        withRepository(store, api) { repository ->
            repository.begin()
            store.failWrite = true
            repository.confirm()
            assertNull(repository.state.value.accountName)
            assertFalse(repository.state.value.pending)
            assertTrue(repository.state.value.storageProblem)
            assertNull(repository.begin())
            repository.confirm()
            assertEquals(1, api.sessionCalls)
            store.failWrite = false
            repository.disconnect()
            assertFalse(repository.state.value.storageProblem)
            assertEquals(StoredLastFmAuth.Empty, store.value)
        }
    }

    @Test fun tokenStorageFailureDoesNotOpenBrowserAndFailedDisconnectKeepsAccount() = runBlocking {
        val store = MemoryStore().apply { failWrite = true }
        withRepository(store) { repository ->
            assertNull(repository.begin())
            assertFalse(repository.state.value.pending)
            assertTrue(repository.state.value.message!!.contains("保存・削除"))
        }
        store.failWrite = false
        store.value = connected
        withRepository(store) { repository ->
            store.failWrite = true
            repository.disconnect()
            assertEquals("test-user", repository.state.value.accountName)
            assertTrue(store.value is StoredLastFmAuth.Connected)
        }
    }

    @Test fun disconnectWaitingForAnExchangeClearsTheResultInsteadOfReconnecting() = runBlocking {
        val store = MemoryStore()
        val api = Api().apply { sessionGate = CompletableDeferred() }
        withRepository(store, api) { repository ->
            repository.begin()
            coroutineScope {
                val confirm = async { repository.confirm() }
                api.sessionStarted.await()
                val disconnect = async { repository.disconnect() }
                api.sessionGate!!.complete(Unit)
                confirm.await()
                disconnect.await()
            }
            assertNull(repository.state.value.accountName)
            assertEquals(StoredLastFmAuth.Empty, store.value)
        }
    }

    @Test fun missingConfigurationDoesNotMakeRequestsAndChangedApiKeyClearsPreviousCredentials() = runBlocking {
        val api = Api()
        withRepository(api = api, config = LastFmConfig("", "")) { repository ->
            assertFalse(repository.state.value.configured)
            assertNull(repository.begin())
            assertEquals(0, api.tokenCalls)
        }
        val store = MemoryStore(connected)
        withRepository(store, config = LastFmConfig("4".repeat(32), "2".repeat(32))) { repository ->
            assertNull(repository.state.value.accountName)
            assertEquals(StoredLastFmAuth.Empty, store.value)
        }
    }

    @Test fun unreadableStorageRequiresResetAndCannotSilentlyStartAuthorization() = runBlocking {
        val store = MemoryStore().apply { failRead = true }
        val api = Api()
        withRepository(store, api) { repository ->
            assertTrue(repository.state.value.storageProblem)
            assertNull(repository.begin())
            assertEquals(0, api.tokenCalls)
            repository.disconnect()
            assertFalse(repository.state.value.storageProblem)
            assertEquals(StoredLastFmAuth.Empty, store.value)
        }
    }
}
