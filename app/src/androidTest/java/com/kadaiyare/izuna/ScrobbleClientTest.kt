package com.kadaiyare.izuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kadaiyare.izuna.data.history.ScrobbleJob
import com.kadaiyare.izuna.data.lastfm.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrobbleClientTest {
    private val config = LastFmConfig("0".repeat(32), "2".repeat(32))
    private val session = StoredLastFmAuth.Connected("test-user", "3".repeat(32), config.apiKey, 1000)
    private val job = ScrobbleJob("id", "session", "test-user", "夜=道&", "じん", "作品", 40_500, 123_456, 145_000)
    private fun response(code: Int = 0, accepted: Int = 1, ignored: Int = 0, timestamp: Long = 123) =
        """{"scrobbles":{"@attr":{"accepted":"$accepted","ignored":"$ignored"},"scrobble":{"timestamp":"$timestamp","ignoredMessage":{"code":"$code","#text":""}}}}"""

    @Test fun sendsStructuredSnapshotAndOriginalUtcTimestamp() = runBlocking {
        var params: Map<String, String> = emptyMap()
        val client = ScrobbleClient(config, ScrobbleTransport { params = it; LastFmResponse(200, response()) })
        assertEquals(ScrobbleResult.Accepted, client.submit(job, session))
        assertEquals("track.scrobble", params["method"])
        assertEquals("123", params["timestamp"])
        assertEquals("40", params["duration"])
        assertEquals(job.title, params["track"])
        assertEquals(job.artist, params["artist"])
        assertEquals(session.sessionKey, params["sk"])
        assertNotNull(params["api_sig"])
        assertFalse(params.containsKey("uri"))
        assertTrue(form(params).contains("%E3%81%98%E3%82%93"))
    }
    @Test fun apiErrorsControlRetryRegardlessOfHttpStatus() = runBlocking {
        for (status in listOf(200, 403, 503)) for (code in listOf(9, 11, 16, 13, 29)) {
            val client = ScrobbleClient(config, ScrobbleTransport { LastFmResponse(status, """{"error":$code}""") })
            val expected = when (code) { 9 -> ScrobbleResult.Reauthenticate; 11, 16 -> ScrobbleResult.Retry; else -> ScrobbleResult.Rejected(code) }
            assertEquals(expected, client.submit(job, session))
        }
    }
    @Test fun ignoredResponsesAreNotReportedAsAccepted() = runBlocking {
        for (code in 1..6) {
            val client = ScrobbleClient(config, ScrobbleTransport { LastFmResponse(200, response(code, 0, 1)) })
            assertEquals(ScrobbleResult.Ignored(code), client.submit(job, session))
        }
    }
    @Test fun inconsistentMalformedAndUnmatchedResponsesAreUncertain() = runBlocking {
        for (body in listOf("not JSON", "{}", response(0, 0, 1), response(timestamp = 124))) {
            val client = ScrobbleClient(config, ScrobbleTransport { LastFmResponse(200, body) })
            assertEquals(ScrobbleResult.Uncertain, client.submit(job, session))
        }
        val failed = ScrobbleClient(config, ScrobbleTransport { LastFmResponse(503, response()) })
        assertEquals(ScrobbleResult.Uncertain, failed.submit(job, session))
    }
    @Test fun connectionFailureRetriesButLostResponseDoesNotAutomaticallyRepeat() = runBlocking {
        val before = ScrobbleClient(config, ScrobbleTransport { throw ScrobbleNetworkException(false) })
        val after = ScrobbleClient(config, ScrobbleTransport { throw ScrobbleNetworkException(true) })
        assertEquals(ScrobbleResult.Retry, before.submit(job, session))
        assertEquals(ScrobbleResult.Uncertain, after.submit(job, session))
    }
    @Test fun differentAccountNeverReachesTheTransport() = runBlocking {
        var called = false
        val client = ScrobbleClient(config, ScrobbleTransport { called = true; LastFmResponse(200, response()) })
        try { client.submit(job.copy(accountName = "other"), session); fail("Account must match") }
        catch (_: IllegalArgumentException) { }
        assertFalse(called)
    }
}
