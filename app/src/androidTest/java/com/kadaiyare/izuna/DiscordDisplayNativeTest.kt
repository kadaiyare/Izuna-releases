package com.kadaiyare.izuna

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.data.discord.*
import com.kadaiyare.izuna.data.settings.SettingsRepository
import com.kadaiyare.izuna.discordruntime.DiscordNative
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit manual opt-in only: publishes fictional metadata to the signed-in Discord profile. */
class DiscordDisplayNativeTest {
    @Test fun manuallyInspectDisplayWithSignedInDiscord() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Manual profile observation only", args.getString("manualDiscordDisplay") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = SettingsRepository.get(instrumentation.targetContext)
        val settings = runBlocking { repository.data.first() }
        val options = DiscordDisplaySettings(args.getString("showAlbum") == "true",
            DiscordTitleFormat.valueOf(args.getString("titleFormat") ?: "SONG"),
            DiscordCompactDisplay.valueOf(args.getString("compactDisplay") ?: "APP_NAME"))
        val text = options.format("表示テストの曲", "Izuna Tests", "表示テストのアルバム")
        try {
            runBlocking { repository.setDiscordSharing(false) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { DiscordNative.prepare(it) }
                // SDK calls and callbacks share this one instrumentation thread.
                val native = DiscordNative()
                val handle = native.create(BuildConfig.DISCORD_APPLICATION_ID)
                try {
                    val now = System.currentTimeMillis()
                    native.publish(handle, text.title.toByteArray(Charsets.UTF_8), text.subtitle.toByteArray(Charsets.UTF_8),
                        true, true, now - 30_000, now + 570_000, statusDisplayType = options.compactDisplay.sdkValue)
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    var result = native.poll(handle)
                    while (result[0] == 1 && SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(50); result = native.poll(handle)
                    }
                    assertEquals("SDK publish failed: ${result.toList()}", 2, result[0])
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("displayPhase", "Published ${options.titleFormat} / ${options.compactDisplay} / album=${options.showAlbum}")
                    })
                    // Keep the transport alive while the operator inspects Discord on the phone.
                    val inspectUntil = SystemClock.elapsedRealtime() + (args.getString("inspectSeconds")?.toLong() ?: 45L) * 1000
                    while (SystemClock.elapsedRealtime() < inspectUntil) { native.poll(handle); Thread.sleep(50) }
                } finally {
                    native.clear(handle)
                    repeat(10) { native.poll(handle); Thread.sleep(50) }
                    native.destroy(handle)
                }
            }
        } finally { runBlocking { repository.setDiscordSharing(settings.discordSharing) } }
    }
}
