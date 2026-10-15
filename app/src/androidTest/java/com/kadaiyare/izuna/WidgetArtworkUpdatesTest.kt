package com.kadaiyare.izuna

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kadaiyare.izuna.widget.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetArtworkUpdatesTest {
    private suspend fun fixture(test: suspend (CoroutineScope) -> Unit) = withContext(Dispatchers.Main.immediate) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try { withTimeout(5000) { test(scope) } } finally { scope.cancel() }
    }
    private suspend fun until(condition: () -> Boolean) { while (!condition()) delay(5) }

    @Test fun slowArtworkNeverReplacesNewerTrackOrPauseState() = runBlocking {
        fixture { scope ->
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val old = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val fresh = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val renders = mutableListOf<Pair<WidgetState, Bitmap?>>()
            val updates = WidgetArtworkUpdates(scope, { uri ->
                if (uri == "old") { started.complete(Unit); finish.await(); old } else fresh
            }) { state, bitmap -> renders += state to bitmap }
            updates.update(WidgetState(title = "A", artworkUri = "old", hasTrack = true, pauseAction = true, status = WidgetStatus.Playing))
            started.await()
            updates.update(WidgetState(title = "B", artworkUri = "new", hasTrack = true, status = WidgetStatus.Paused))
            finish.complete(Unit)
            until { renders.lastOrNull()?.second === fresh }
            assertFalse(renders.any { it.second === old })
            assertEquals("B", renders.last().first.title)
            assertFalse(renders.last().first.pauseAction)
        }
    }

    @Test fun renderingControlsDoesNotWaitForArtworkAndRepeatedEventsDoNotDecodeAgain() = runBlocking {
        fixture { scope ->
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            var reads = 0
            val renders = mutableListOf<Pair<WidgetState, Bitmap?>>()
            val updates = WidgetArtworkUpdates(scope, { reads++; started.complete(Unit); finish.await(); bitmap }) { state, image -> renders += state to image }
            val playing = WidgetState(title = "A", artworkUri = "cover", hasTrack = true, pauseAction = true, status = WidgetStatus.Playing)
            updates.update(playing); started.await()
            assertEquals(playing, renders.single().first); assertNull(renders.single().second)
            repeat(5) { updates.update(playing) }
            finish.complete(Unit); until { renders.lastOrNull()?.second === bitmap }
            val before = renders.size
            updates.update(playing); yield(); assertEquals(before, renders.size)
            updates.update(playing.stopped()); until { renders.last().first.status == WidgetStatus.Paused }
            assertSame(bitmap, renders.last().second); assertEquals(1, reads)
            updates.update(playing.stopped(), force = true); until { renders.size > before + 1 }
            assertEquals(1, reads)
        }
    }

    @Test fun missingArtworkFallsBackAndNewTrackDoesNotReusePreviousBitmap() = runBlocking {
        fixture { scope ->
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val renders = mutableListOf<Pair<WidgetState, Bitmap?>>()
            val updates = WidgetArtworkUpdates(scope, { if (it == "good") bitmap else throw java.io.IOException("Fixture unreadable") }) { state, image -> renders += state to image }
            updates.update(WidgetState(title = "A", artworkUri = "good", hasTrack = true))
            until { renders.lastOrNull()?.second === bitmap }
            updates.update(WidgetState(title = "B", artworkUri = "bad", hasTrack = true))
            until { renders.last().first.title == "B" }
            assertNull(renders.last().second)
            updates.update(WidgetState(title = "C", hasTrack = true)); until { renders.last().first.title == "C" }
            assertNull(renders.last().second)
        }
    }
}
