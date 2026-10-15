package com.kadaiyare.izuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.data.playlist.PlaylistRepository
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistRepositoryTest {
    @Test fun editsAreDurableDeduplicatedAndDoNotLoseConcurrentAdds() = runBlocking {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "playlist-test-${System.nanoTime()}.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var repository = PlaylistRepository.create(file, scope)
            assertTrue(repository.data.first().isEmpty())
            repository.create("  お気に入り  ")
            val id = repository.data.first().single().id
            coroutineScope {
                launch { repository.addTracks(id, listOf("content://audio/1", "content://audio/1")) }
                launch { repository.addTracks(id, listOf("content://audio/2")) }
            }
            repository.rename(id, "通勤")
            assertEquals(setOf("content://audio/1", "content://audio/2"), repository.data.first().single().trackUris.toSet())
            repository.removeTrack(id, "content://audio/1")
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            repository = PlaylistRepository.create(file, scope)
            assertEquals("通勤", repository.data.first().single().name)
            assertEquals(listOf("content://audio/2"), repository.data.first().single().trackUris)
            repository.delete(id)
            assertTrue(repository.data.first().isEmpty())
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }
}
