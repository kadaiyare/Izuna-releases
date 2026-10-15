package com.kadaiyare.izuna

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.data.discord.DiscordPresence
import com.kadaiyare.izuna.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiscordOptOutPlaybackTest {
    @Test fun actualPlaybackWithSharingOffDoesNotMapSdkOrBridge() {
        assertTrue(Os.sysconf(OsConstants._SC_PAGESIZE) in listOf(4096L, 16384L))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = DiscordPresence.get(instrumentation.targetContext)
        instrumentation.runOnMainSync {
            manager.setEnabled(false)
        }
        val settings = SettingsRepository.get(instrumentation.targetContext)
        val oldSharing = runBlocking { settings.data.first().discordSharing }
        try {
            runBlocking { settings.setDiscordSharing(false) }
            // Exercise real foreground/background playback without loading an external SDK.
            PlaybackIntegrationTest().localFlacPlaysAndAdvancesInBackgroundWithMediaNotification()
        } finally {
            runBlocking { settings.setDiscordSharing(oldSharing) }
            instrumentation.runOnMainSync { manager.setEnabled(false) }
        }
        val maps = File("/proc/self/maps").readText()
        assertFalse(maps.contains("libdiscord_partner_sdk.so"))
        assertFalse(maps.contains("libizuna_discord_runtime.so"))
    }
}
