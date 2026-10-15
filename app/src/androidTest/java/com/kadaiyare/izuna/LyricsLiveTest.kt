package com.kadaiyare.izuna

import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.LyricsQuery
import com.kadaiyare.izuna.core.model.parseLrc
import com.kadaiyare.izuna.data.lyrics.LrclibProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in; public test metadata only, never device-library metadata or song files. */
class LyricsLiveTest {
    @Test fun publicTrackReturnsSyncedLyricsThroughTheRealApi() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyLyrics") == "true")
        val result = withTimeout(15_000) { LrclibProvider().find(LyricsQuery("Another One Bites the Dust", "Queen", "The Game", 215)) }
        assertNotNull("Known public track did not match", result)
        assertFalse("No line timestamps", parseLrc(result!!.synced).isEmpty())
        assertFalse(result.instrumental)
    }
}
