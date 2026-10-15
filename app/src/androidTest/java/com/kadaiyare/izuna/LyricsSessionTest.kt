package com.kadaiyare.izuna

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.*
import com.kadaiyare.izuna.data.lyrics.*
import com.kadaiyare.izuna.data.settings.SettingsRepository
import com.kadaiyare.izuna.ui.player.LyricsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LyricsSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private suspend fun fixture(test: suspend (LyricsViewModel, SettingsRepository, CompletableDeferred<Unit>, () -> Int) -> Unit) {
        val root = File(app.cacheDir, "lyrics-session-${System.nanoTime()}").apply { mkdirs() }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val settings = SettingsRepository.create(root.resolve("settings.preferences_pb"), scope)
        settings.setOnlineLyrics(true)
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val repository = LyricsRepository.create(root, scope, LyricsProvider { query ->
            calls++
            if (query.title == "A") {
                entered.complete(Unit)
                withContext(NonCancellable) { delay(300) }
            }
            LyricsDocument("LRCLIB", if (query.title == "A") 1 else 2, query.title, "[00:01]${query.title}")
        })
        lateinit var viewModel: LyricsViewModel
        val store = ViewModelStore()
        instrumentation.runOnMainSync {
            viewModel = LyricsViewModel(app, repository, settings) { uri, duration -> LyricsQuery(uri, "Test", "Album", (duration / 1000).toInt()) }
            store.put("lyrics", viewModel)
        }
        try {
            withTimeout(5000) { viewModel.state.first { it.online } }
            test(viewModel, settings, entered) { calls }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            job.cancelAndJoin(); root.deleteRecursively()
        }
    }
    @Test fun delayedPreviousTrackNeverReplacesTheNewTrack() = runBlocking {
        fixture { model, _, entered, _ ->
            instrumentation.runOnMainSync { model.show("A", 180_000) }
            withTimeout(5000) { entered.await() }
            instrumentation.runOnMainSync { model.show("B", 180_000) }
            val state = withTimeout(5000) { model.state.first { !it.loading && it.document?.recordId == 2L } }
            assertEquals("B", state.uri); assertEquals("B", state.document!!.plain)
            delay(400)
            assertEquals(2L, model.state.value.document?.recordId)
        }
    }
    @Test fun closingLyricsCancelsPendingResults() = runBlocking {
        fixture { model, _, entered, _ ->
            instrumentation.runOnMainSync { model.show("A", 180_000) }
            withTimeout(5000) { entered.await() }
            instrumentation.runOnMainSync { model.hide() }
            delay(500)
            assertNull(model.state.value.document)
            assertFalse(model.state.value.loading)
            instrumentation.runOnMainSync { assertFalse(model.isVisible) }
        }
    }
    @Test fun disablingOnlineLookupCancelsPendingFetchAndRetainsOfflineMode() = runBlocking {
        fixture { model, settings, entered, calls ->
            instrumentation.runOnMainSync { model.show("A", 180_000) }
            withTimeout(5000) { entered.await() }
            settings.setOnlineLyrics(false)
            val state = withTimeout(5000) { model.state.first { !it.online && !it.loading } }
            assertNull(state.document)
            delay(400)
            assertNull(model.state.value.document)
            assertEquals(1, calls())
        }
    }
}
