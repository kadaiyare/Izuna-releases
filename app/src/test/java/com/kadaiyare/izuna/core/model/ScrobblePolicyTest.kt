package com.kadaiyare.izuna.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobblePolicyTest {
    private val track = Track("1", "content://media/external/audio/media/1", "曲", "歌手", "アルバム", 60_000, null, 1, 1)

    @Test fun exactlyThirtySecondsIsTooShortEvenWhenFullyPlayed() {
        assertNull(ScrobblePolicy.requiredListeningMs(30_000))
        assertFalse(ScrobblePolicy.qualifies(30_000, 30_000))
        assertFalse(ScrobblePolicy.qualifies(12_000, 240_000))
    }

    @Test fun unknownAndNegativeDurationNeverQualify() {
        assertNull(ScrobblePolicy.requiredListeningMs(0))
        assertNull(ScrobblePolicy.requiredListeningMs(-1))
        assertFalse(ScrobblePolicy.qualifies(0, Long.MAX_VALUE))
    }

    @Test fun halfDurationQualifiesAtExactBoundary() {
        assertEquals(30_000L, ScrobblePolicy.requiredListeningMs(60_000))
        assertFalse(ScrobblePolicy.qualifies(60_000, 29_999))
        assertTrue(ScrobblePolicy.qualifies(60_000, 30_000))
    }

    @Test fun oddMillisecondsRoundUpInsteadOfQualifyingEarly() {
        assertEquals(15_001L, ScrobblePolicy.requiredListeningMs(30_001))
        assertFalse(ScrobblePolicy.qualifies(30_001, 15_000))
        assertTrue(ScrobblePolicy.qualifies(30_001, 15_001))
    }

    @Test fun longTracksUseFourMinutesIncludingEightMinuteBoundary() {
        for (length in listOf(480_000L, 600_000L, Long.MAX_VALUE)) {
            assertEquals(240_000L, ScrobblePolicy.requiredListeningMs(length))
            assertFalse(ScrobblePolicy.qualifies(length, 239_999))
            assertTrue(ScrobblePolicy.qualifies(length, 240_000))
        }
    }

    @Test fun negativeListeningTimeNeverQualifies() {
        assertFalse(ScrobblePolicy.qualifies(60_000, -1))
    }

    @Test fun pauseTimeAndChangedTrackLengthDoNotCountAsListening() {
        val tracker = ListeningTracker { "session" }
        tracker.update(track, true, 0, 1_000)
        tracker.update(track, false, 10_000, 11_000)
        val paused = tracker.update(track, false, 100_000, 101_000).single()
        assertEquals(10_000L, paused.listenedMs)
        assertFalse(paused.qualifiedForScrobble)
        tracker.update(track, true, 100_000, 101_000)
        assertTrue(tracker.update(track, true, 120_000, 121_000).single().qualifiedForScrobble)
    }

    @Test fun repeatStartsWithNoQualificationFromPreviousPlay() {
        var count = 0
        val tracker = ListeningTracker { "session-${count++}" }
        tracker.update(track, true, 0, 1_000)
        val repeated = tracker.update(track, true, 30_000, 31_000, boundary = true)
        assertTrue(repeated[0].qualifiedForScrobble)
        assertFalse(repeated[1].qualifiedForScrobble)
    }

    @Test fun qualificationDoesNotRequireTrackToEnd() {
        val session = ListeningSession("session", track, 1_000, 31_000, listenedMs = 30_000)
        assertNull(session.endedAtMs)
        assertTrue(session.qualifiedForScrobble)
    }

    @Test fun blankAndFallbackTagsAreNotSendableMetadata() {
        assertFalse(ScrobbleMetadata("", "歌手").valid())
        assertFalse(ScrobbleMetadata("タイトル不明", "歌手").valid())
        assertFalse(ScrobbleMetadata("曲", "アーティスト不明").valid())
        assertFalse(ScrobbleMetadata("曲", " <UNKNOWN> ").valid())
        assertTrue(ScrobbleMetadata("曲 🎵", "歌手").valid())
    }
}
