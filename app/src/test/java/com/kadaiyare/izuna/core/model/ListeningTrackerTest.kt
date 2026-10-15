package com.kadaiyare.izuna.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningTrackerTest {
    private val track = Track("1", "content://media/external/audio/media/1", "曲", "歌手", "アルバム", 12_000, null, 1, 1)
    private var sequence = 0
    private val tracker = ListeningTracker { "session-${sequence++}" }

    @Test fun preparationAndRestoredPauseDoNotCreateHistory() {
        assertTrue(tracker.update(track, false, 0, 1_000, boundary = true).isEmpty())
        assertTrue(tracker.update(track, false, 5_000, 6_000).isEmpty())
        assertTrue(tracker.update(null, false, 6_000, 7_000, finished = true).isEmpty())
    }

    @Test fun pauseAndBufferingAreExcludedAndResumeKeepsSession() {
        val first = tracker.update(track, true, 0, 1_000).single()
        assertEquals(1_000L, tracker.update(track, false, 1_000, 2_000).single().listenedMs)
        assertEquals(1_000L, tracker.update(track, false, 8_000, 9_000).single().listenedMs)
        val resumed = tracker.update(track, true, 10_000, 11_000).single()
        assertEquals(first.id, resumed.id)
        assertEquals(1_250L, tracker.update(track, false, 10_250, 11_250).single().listenedMs)
    }

    @Test fun repeatedEventsDoNotDoubleCountElapsedTime() {
        tracker.update(track, true, 100, 1_000)
        tracker.update(track, true, 600, 1_500)
        tracker.update(track, true, 600, 1_500)
        assertEquals(500L, tracker.update(track, false, 600, 1_500).single().listenedMs)
    }

    @Test fun sameTrackRepeatClosesOldSessionAndStartsAnother() {
        val first = tracker.update(track, true, 0, 1_000).single()
        val repeated = tracker.update(track, true, 1_000, 2_000, boundary = true)
        assertEquals(2, repeated.size)
        assertEquals(first.id, repeated[0].id)
        assertEquals(2_000L, repeated[0].endedAtMs)
        assertEquals(1_000L, repeated[0].listenedMs)
        assertEquals("session-1", repeated[1].id)
        assertEquals(0L, repeated[1].listenedMs)
        assertNull(repeated[1].endedAtMs)
    }

    @Test fun switchingToUnplayedTrackClosesOnlyPreviousSession() {
        tracker.update(track, true, 0, 1_000)
        val other = track.copy(id = "2", uri = "content://media/external/audio/media/2")
        val closed = tracker.update(other, false, 200, 1_200, boundary = true).single()
        assertEquals(track, closed.track)
        assertEquals(200L, closed.listenedMs)
        assertTrue(tracker.update(other, false, 500, 1_500).isEmpty())
        assertEquals(other, tracker.update(other, true, 600, 1_600).single().track)
    }

    @Test fun stopClosesOnceAndRestartMakesNewSession() {
        val first = tracker.update(track, true, 0, 1_000).single()
        val closed = tracker.update(track, false, 500, 1_500, finished = true).single()
        assertEquals(first.id, closed.id)
        assertEquals(1_500L, closed.endedAtMs)
        assertTrue(tracker.update(track, false, 600, 1_600, finished = true).isEmpty())
        assertEquals("session-1", tracker.update(track, true, 1_000, 2_000).single().id)
    }

    @Test fun wallClockAdjustmentDoesNotChangeListeningTimeOrReverseEndTime() {
        tracker.update(track, true, 0, 10_000)
        tracker.update(track, true, 1_000, 11_000)
        val closed = tracker.update(track, false, 2_000, 1_000, finished = true).single()
        assertEquals(2_000L, closed.listenedMs)
        assertEquals(11_000L, closed.endedAtMs)
    }

    @Test fun metadataChangesKeepSessionWithoutUsingTrackDurationAsListeningTime() {
        val first = tracker.update(track, true, 0, 1_000).single()
        val changed = track.copy(title = "更新した曲名", durationMs = 600_000)
        val updated = tracker.update(changed, true, 200, 1_200).single()
        assertEquals(first.id, updated.id)
        assertEquals(changed, updated.track)
        assertEquals(200L, updated.listenedMs)
    }
}
