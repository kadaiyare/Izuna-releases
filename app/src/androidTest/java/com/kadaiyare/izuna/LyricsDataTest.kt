package com.kadaiyare.izuna

import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.*
import com.kadaiyare.izuna.data.lyrics.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** Fake provider responses; never sends device tags or lyric text to external services. */
class LyricsDataTest {
    private val query = LyricsQuery("Test Song", "Test Artist", "Test Album", 180)
    private fun response(q: LyricsQuery = query, synced: String? = "[00:01]First\n[00:05]Second", plain: String? = "First\nSecond",
        instrumental: Boolean = false) = JSONObject().put("id", 123).put("trackName", q.title).put("artistName", q.artist)
        .put("albumName", q.album).put("duration", q.durationSeconds).put("syncedLyrics", synced ?: JSONObject.NULL)
        .put("plainLyrics", plain ?: JSONObject.NULL).put("instrumental", instrumental).toString()

    @Test fun exactLookupEncodesTagsAndRejectsWrongVersions() = runBlocking {
        var endpoint = ""
        val special = query.copy(title = "A&B / 曲")
        val provider = LrclibProvider({ endpoint = it; LyricsHttpResponse(200, response(special)) }, intervalMs = 0)
        assertNotNull(provider.find(special))
        assertTrue(endpoint.contains("track_name=A%26B+%2F+%E6%9B%B2"))
        for (wrong in listOf(query.copy(title = "Other"), query.copy(artist = "Other"),
            query.copy(album = "Live"), query.copy(durationSeconds = 183))) {
            assertNull(LrclibProvider.parseResponse(response(wrong), query))
        }
        assertNotNull(LrclibProvider.parseResponse(response(query.copy(durationSeconds = 182)), query))
    }
    @Test fun missingLyricsPlainLyricsAndInstrumentalsAreDistinct() = runBlocking {
        assertNull(LrclibProvider({ LyricsHttpResponse(404) }, intervalMs = 0).find(query))
        val plain = LrclibProvider.parseResponse(response(synced = "invalid", plain = "Plain only"), query)!!
        assertNull(plain.synced); assertEquals("Plain only", plain.plain)
        assertTrue(LrclibProvider.parseResponse(response(synced = null, plain = null, instrumental = true), query)!!.instrumental)
        assertNull(LrclibProvider.parseResponse(response(synced = null, plain = null), query))
    }
    @Test fun retryAfterIsHonoredWithoutAnotherRequest() = runBlocking {
        var calls = 0
        var now = 0L
        val provider = LrclibProvider({ calls++; if (calls == 1) LyricsHttpResponse(429, retryAfterSeconds = 60)
            else LyricsHttpResponse(200, response()) }, { now }, 0)
        repeat(2) {
            try { provider.find(query); fail("Rate limit expected") } catch (_: LyricsRateLimited) { }
        }
        assertEquals(1, calls)
        now = 60_000
        assertNotNull(provider.find(query)); assertEquals(2, calls)
    }
    @Test fun cancellationAndServerErrorsDoNotBecomeSuccessfulMisses() = runBlocking {
        try { LrclibProvider({ throw CancellationException() }, intervalMs = 0).find(query); fail() }
        catch (_: CancellationException) { }
        try { LrclibProvider({ LyricsHttpResponse(500) }, intervalMs = 0).find(query); fail() }
        catch (_: IOException) { }
        var called = false
        assertNull(LrclibProvider({ called = true; LyricsHttpResponse(404) }).find(query.copy(durationSeconds = 0)))
        assertFalse(called)
    }
    @Test fun cachedLyricsAndFileSpecificAdjustmentsSurviveReopeningOffline() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "lyrics-data-${System.nanoTime()}").apply { mkdirs() }
        var job = SupervisorJob()
        var calls = 0
        try {
            val repository = LyricsRepository.create(root, CoroutineScope(job + Dispatchers.IO), LyricsProvider {
                calls++; LrclibProvider.parseResponse(response(), query)
            })
            assertNull(repository.load(query, false).document)
            val first = repository.load(query, true)
            assertFalse(first.cached); assertNotNull(first.document)
            assertEquals(1, calls)
            repository.adjust("file-A", 500)
            repository.adjust("file-A", 500)
            assertEquals(0, repository.adjustment("file-B"))
            job.cancelAndJoin()
            job = SupervisorJob()
            val reopened = LyricsRepository.create(root, CoroutineScope(job + Dispatchers.IO), LyricsProvider { fail("Offline must not fetch"); null })
            val cached = reopened.load(query, false)
            assertTrue(cached.cached); assertEquals(first.document, cached.document)
            assertEquals(1000, reopened.adjustment("file-A"))
            assertEquals(10_000, reopened.adjust("file-A", 50_000))
            assertEquals(0, reopened.adjust("file-A", null, reset = true))
        } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }
    @Test fun corruptCacheAndTransientFailuresCanBeRecovered() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "lyrics-corrupt-${System.nanoTime()}").apply { mkdirs() }
        val job = SupervisorJob()
        var calls = 0
        try {
            val repository = LyricsRepository.create(root, CoroutineScope(job + Dispatchers.IO), LyricsProvider {
                calls++; if (calls == 1) throw IOException() else LrclibProvider.parseResponse(response(), query)
            })
            try { repository.load(query, true); fail() } catch (_: IOException) { }
            assertNotNull(repository.load(query, true).document)
            root.resolve("lyrics/${LyricsRepository.key(query)}.json").writeText("corrupt")
            assertNull(repository.load(query, false).document)
            assertNotNull(repository.load(query, true).document)
            assertEquals(3, calls)
        } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }
}
