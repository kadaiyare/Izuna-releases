package com.kadaiyare.izuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import com.kadaiyare.izuna.data.discord.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverLookupSessionTest {
    private val tags = ScrobbleMetadata("Test", "Artist", "Album")
    private fun song(position: Long = 0) = PresenceSong("Test", "Artist", position, 180_000, position)

    @Test fun textIsImmediateAndImageUsesFreshPosition() = runBlocking { withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val published = mutableListOf<PresenceSong>()
            var current = song().copy(compactDisplay = DiscordCompactDisplay.TITLE)
            val session = CoverLookupSession(scope, { result.await() }, published::add)
            session.start(tags, true) { current }
            assertEquals(listOf(current), published)
            current = current.copy(positionMs = 42_000); result.complete("https://archive.org/front.jpg")
            withTimeout(2000) { while (published.size != 2) delay(10) }
            assertEquals(42_000L, published.last().positionMs)
            assertNotNull(published.last().imageUrl)
            assertEquals(DiscordCompactDisplay.TITLE, published.last().compactDisplay)
        } finally { scope.cancel() }
    } }
    @Test fun cancellationDropsEvenANonCooperativeOldResult() = runBlocking { withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>(); val started = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val published = mutableListOf<PresenceSong>()
            val session = CoverLookupSession(scope, { started.complete(Unit); withContext(NonCancellable) { result.await() } }, published::add)
            session.start(tags, true) { song() }; started.await()
            session.cancel(); session.start(tags, false) { song().copy(title = "Next") }
            result.complete("https://archive.org/old.jpg"); delay(100)
            assertEquals(listOf("Test", "Next"), published.map { it.title })
            assertTrue(published.all { it.imageUrl == null })
        } finally { scope.cancel() }
    } }
    @Test fun disabledSearchAndStoppedPlaybackNeverCallProvider() = runBlocking { withContext(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val published = mutableListOf<PresenceSong>()
            val session = CoverLookupSession(scope, { fail("No lookup expected"); null }, published::add)
            session.start(tags, false) { song() }; session.start(tags, true) { null }
            assertEquals(1, published.size)
        } finally { scope.cancel() }
    } }
    @Test fun networkFailureLeavesTextSharingIntact() = runBlocking { withContext(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val published = mutableListOf<PresenceSong>()
            val session = CoverLookupSession(scope, { throw java.io.IOException() }, published::add)
            session.start(tags, true) { song() }; delay(100)
            assertEquals(listOf(song()), published)
        } finally { scope.cancel() }
    } }
}
