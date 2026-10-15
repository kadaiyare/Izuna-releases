package com.kadaiyare.izuna

import com.kadaiyare.izuna.data.lastfm.LastFmConfig
import com.kadaiyare.izuna.data.lastfm.StoredLastFmAuth
import org.junit.Assert.*
import org.junit.Test

class LastFmAuthTest {
    private val config = LastFmConfig("0".repeat(32), "2".repeat(32))

    @Test fun signatureMatchesFixedFixtureAndIgnoresTransportParameters() {
        val params = mapOf("token" to "1".repeat(32), "method" to "auth.getSession")
        assertEquals("a8f46bba7170fc966a71b18cb21f263c", config.signed(params)["api_sig"])
        assertEquals(config.signed(params)["api_sig"], config.signed(params + mapOf("format" to "json", "callback" to "ignored", "api_sig" to "ignored"))["api_sig"])
    }

    @Test fun signatureUsesUtf8AndAlphabeticalParameterNames() {
        val params = mapOf("token" to "1".repeat(32), "method" to "auth.getSession", "artist" to "じん")
        assertEquals("49efdfb2f166bf684ef727ae3160fa35", config.signed(params)["api_sig"])
    }

    @Test fun authorizationAlwaysUsesLastFmHttpsAndNeverExposesSecret() {
        val url = config.authorizationUrl("1".repeat(32))
        assertTrue(url.startsWith("https://www.last.fm/api/auth/?"))
        assertFalse(url.contains("2".repeat(32)))
        assertFalse(url.contains("api_sig"))
    }

    @Test fun unconfiguredBuildAndMalformedTokenAreRejected() {
        assertFalse(LastFmConfig("", "").configured)
        assertFalse(LastFmConfig("x".repeat(32), "2".repeat(32)).configured)
        assertThrows(IllegalArgumentException::class.java) { config.authorizationUrl("broken&redirect=elsewhere") }
    }

    @Test fun pendingTokenExpiresAfterSixtyMinutesAndRejectsClockRollback() {
        val pending = StoredLastFmAuth.Pending("1".repeat(32), 1000, "0".repeat(32))
        assertTrue(pending.validAt(1000))
        assertTrue(pending.validAt(3_600_999))
        assertFalse(pending.validAt(3_601_000))
        assertFalse(pending.validAt(999))
    }

    @Test fun debugStringsRedactAllCredentials() {
        assertEquals("LastFmConfig(redacted)", config.toString())
        assertEquals("Pending(redacted)", StoredLastFmAuth.Pending("1".repeat(32), 1000, "0".repeat(32)).toString())
        assertEquals("Connected(redacted)", StoredLastFmAuth.Connected("test-user", "3".repeat(32), "0".repeat(32)).toString())
    }
}
