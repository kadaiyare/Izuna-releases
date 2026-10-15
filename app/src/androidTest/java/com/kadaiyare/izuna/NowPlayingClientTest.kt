package com.kadaiyare.izuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import com.kadaiyare.izuna.data.lastfm.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingClientTest {
    private val config = LastFmConfig("0".repeat(32), "2".repeat(32))
    private val session = StoredLastFmAuth.Connected("test-user", "3".repeat(32), config.apiKey, 1000)
    private val metadata = ScrobbleMetadata("夜=道&", "じん", "作品")
    private fun response(code: Int = 0) = """{"nowplaying":{"track":{"corrected":"1","#text":"Corrected"},"ignoredMessage":{"code":"$code","#text":""}}}"""

    @Test fun sendsSignedStructuredTagsWithoutTimestampOrLocalUri() = runBlocking {
        var parameters = emptyMap<String, String>()
        val client = NowPlayingClient(config, ScrobbleTransport { parameters = it; LastFmResponse(200, response()) })
        assertEquals(NowPlayingResult.Accepted, client.submit(metadata, 12_500, session))
        assertEquals("track.updateNowPlaying", parameters["method"])
        assertEquals(metadata.title, parameters["track"])
        assertEquals(metadata.artist, parameters["artist"])
        assertEquals(metadata.album, parameters["album"])
        assertEquals("12", parameters["duration"])
        assertEquals(session.sessionKey, parameters["sk"])
        assertEquals(config.signed(parameters.filterKeys { it !in setOf("api_sig", "api_key", "format") })["api_sig"], parameters["api_sig"])
        assertEquals("json", parameters["format"])
        assertFalse(parameters.containsKey("timestamp"))
        assertFalse(parameters.containsKey("uri"))
        assertTrue(form(parameters).contains("%E3%81%98%E3%82%93"))
        assertEquals("夜=道&", metadata.title) // Server corrections never mutate original tags.
    }

    @Test fun omitsUnknownDurationAndAbsentAlbum() = runBlocking {
        val client = NowPlayingClient(config, ScrobbleTransport {
            assertFalse(it.containsKey("duration")); assertFalse(it.containsKey("album"))
            LastFmResponse(200, response())
        })
        assertEquals(NowPlayingResult.Accepted, client.submit(metadata.copy(album = null), 0, session))
    }

    @Test fun recognizesIgnoredAndInvalidSessionResponses() = runBlocking {
        for (code in 1..5) {
            val client = NowPlayingClient(config, ScrobbleTransport { LastFmResponse(200, response(code)) })
            assertEquals(NowPlayingResult.Ignored, client.submit(metadata, 12_000, session))
        }
        for (status in listOf(200, 403)) {
            val client = NowPlayingClient(config, ScrobbleTransport { LastFmResponse(status, """{"error":9}""") })
            assertEquals(NowPlayingResult.Reauthenticate, client.submit(metadata, 12_000, session))
        }
    }

    @Test fun allOtherErrorsAndMalformedResponsesFailWithoutInternalRetry() = runBlocking {
        for (body in listOf("not JSON", "{}", response(6), """{"error":11}""", """{"error":16}""", """{"error":29}""")) {
            var calls = 0
            val client = NowPlayingClient(config, ScrobbleTransport { calls++; LastFmResponse(200, body) })
            assertEquals(NowPlayingResult.Failed, client.submit(metadata, 12_000, session))
            assertEquals(1, calls)
        }
        val http = NowPlayingClient(config, ScrobbleTransport { LastFmResponse(503, response()) })
        val network = NowPlayingClient(config, ScrobbleTransport { throw IOException("Fixture network loss") })
        assertEquals(NowPlayingResult.Failed, http.submit(metadata, 12_000, session))
        assertEquals(NowPlayingResult.Failed, network.submit(metadata, 12_000, session))
    }

    @Test fun invalidMetadataAndDifferentApiKeyNeverReachTransport() = runBlocking {
        var calls = 0
        val client = NowPlayingClient(config, ScrobbleTransport { calls++; LastFmResponse(200, response()) })
        for ((tags, auth) in listOf(metadata.copy(title = "") to session,
            metadata to StoredLastFmAuth.Connected("other", "3".repeat(32), "4".repeat(32), 1000))) {
            try { client.submit(tags, 12_000, auth); fail("Invalid input must fail") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, calls)
    }

    @Test fun cancellationPropagatesInsteadOfBecomingNotificationFailure() = runBlocking {
        val client = NowPlayingClient(config, ScrobbleTransport { throw CancellationException() })
        try { client.submit(metadata, 12_000, session); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }
}
