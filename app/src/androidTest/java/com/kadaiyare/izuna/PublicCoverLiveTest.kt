package com.kadaiyare.izuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import com.kadaiyare.izuna.data.discord.PublicCoverProvider
import com.kadaiyare.izuna.data.discord.fetchCoverJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only: sends known public metadata, never device-library tags or RPC. */
@RunWith(AndroidJUnit4::class)
class PublicCoverLiveTest {
    @Test fun publicSongResolvesThroughTheRealServices() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyPublicCover") == "true")
        val diagnostics = mutableListOf<String>()
        val image = withTimeout(45_000) {
            PublicCoverProvider(json = { endpoint ->
                try {
                    fetchCoverJson(endpoint).also { diagnostics += "${java.net.URI(endpoint).host}: ${it?.length ?: 0} bytes" }
                } catch (error: Exception) {
                    diagnostics += "${error.javaClass.simpleName}: ${error.message}"
                    throw error
                }
            }).find(ScrobbleMetadata("Another One Bites the Dust", "Queen", "The Game"))
        }
        assertNotNull(diagnostics.joinToString(), image)
        assertTrue(image!!.startsWith("https://coverartarchive.org/") || image.startsWith("https://archive.org/"))
    }
}
