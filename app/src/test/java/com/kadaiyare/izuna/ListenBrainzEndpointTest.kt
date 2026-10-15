package com.kadaiyare.izuna

import com.kadaiyare.izuna.data.listenbrainz.listenBrainzRoot
import com.kadaiyare.izuna.data.listenbrainz.validListenBrainzToken
import com.kadaiyare.izuna.data.listenbrainz.listenBrainzQualifies
import org.junit.Assert.*
import org.junit.Test

class ListenBrainzEndpointTest {
    @Test fun rootsAndVersionedProxyPrefixesNormalizeWithoutLosingCase() {
        assertEquals("https://example.com", listenBrainzRoot(" HTTPS://EXAMPLE.COM:443/1/ "))
        assertEquals("https://example.com:8443/API/listenbrainz", listenBrainzRoot("https://example.com:8443/API/listenbrainz/1"))
        assertEquals("https://[::1]:8443", listenBrainzRoot("https://[::1]:8443/"))
    }
    @Test fun unsafeOrAmbiguousAddressesAreRejected() {
        listOf("http://example.com", "file:///tmp", "https://user:password@example.com", "https://example.com?token=secret",
            "https://example.com/#fragment", "https://example.com/../api", "https://example.com/%2e%2e/api",
            "https://example.com:0", "https://example.com:65536", "https://example.com/1/submit-listens").forEach {
            assertTrue(it, runCatching { listenBrainzRoot(it) }.isFailure)
        }
    }
    @Test fun tokensCannotInjectHeadersAndAreNotRestrictedToUuidShape() {
        assertTrue(validListenBrainzToken("compatible-token_123"))
        assertFalse(validListenBrainzToken("token\r\nX-Test: value"))
        assertFalse(validListenBrainzToken(""))
        assertFalse(validListenBrainzToken("x".repeat(513)))
    }
    @Test fun listenThresholdCountsHalfOrFourMinutesIncludingShortTracks() {
        assertFalse(listenBrainzQualifies(0, 1000))
        assertFalse(listenBrainzQualifies(12_001, 6000))
        assertTrue(listenBrainzQualifies(12_001, 6001))
        assertFalse(listenBrainzQualifies(600_000, 239_999))
        assertTrue(listenBrainzQualifies(600_000, 240_000))
    }
}
