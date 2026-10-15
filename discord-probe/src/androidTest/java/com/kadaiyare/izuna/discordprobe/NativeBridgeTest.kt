package com.kadaiyare.izuna.discordprobe

import android.content.pm.PackageManager
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.socialsdk.DiscordSocialSdkInit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeBridgeTest {
    private fun discordAbsent(): Boolean = try {
        InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .getPackageInfo("com.discord", PackageManager.PackageInfoFlags.of(0))
        false
    } catch (_: PackageManager.NameNotFoundException) { true }

    private fun withNative(block: (DiscordNative) -> Unit) {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        assumeTrue("Use an explicit diagnostic build to test the unresolved 16 KB RELRO alignment",
            pageSize == 4096L || (pageSize == 16384L && BuildConfig.DIAGNOSE_16KB))
        ActivityScenario.launch(NativeTestActivity::class.java).use { scenario ->
            scenario.onActivity { DiscordSocialSdkInit.setEngineActivity(it); DiscordNative.load() }
            block(DiscordNative()) // Client and callbacks stay on this one instrumentation thread.
        }
    }

    @Test fun packagedVersionAndRepeatedClientLifetime() = withNative { native ->
        assertEquals(BuildConfig.DISCORD_EXPECTED_VERSION, native.version())
        repeat(5) {
            val handle = native.create(BuildConfig.DISCORD_APPLICATION_ID)
            assertNotEquals(0L, handle)
            try { assertEquals(0, native.poll(handle)[0]) } finally { native.destroy(handle) }
        }
    }

    @Test fun invalidMusicTimestampsDoNotStartRpc() = withNative { native ->
        val handle = native.create(BuildConfig.DISCORD_APPLICATION_ID)
        try {
            for ((start, end) in listOf(0L to 1L, -1L to 2L, 100L to 99L, 100L to 100L)) {
                assertThrows(IllegalStateException::class.java) {
                    native.publish(handle, "検証".toByteArray(), "Izuna Tests".toByteArray(), true, true, start, end)
                }
                assertEquals("Invalid timestamps must not publish to a signed-in account", 0, native.poll(handle)[0])
            }
        } finally { native.destroy(handle) }
    }

    @Test fun uninstalledDiscordReturnsFailureWithUtf8Tags() {
        // Never publish test data when a signed-in Discord app could be available.
        assumeTrue("Skip RPC test on devices with Discord installed", discordAbsent())
        withNative { native ->
            val handle = native.create(BuildConfig.DISCORD_APPLICATION_ID)
            try {
                native.publish(handle, "検証🎵".toByteArray(Charsets.UTF_8), "架空の歌手".toByteArray(Charsets.UTF_8), false)
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000L
                var status = native.poll(handle)
                while (status[0] == 1 && android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(50)
                    status = native.poll(handle)
                }
                assertEquals("No Discord client must never succeed", 3, status[0])
                assertNotEquals(0, status[1])
            } finally { native.clear(handle); native.destroy(handle) }
        }
    }

    @Test fun clearPreventsPendingCallbackFromRestoringOldStatus() {
        assumeTrue("Skip RPC test on devices with Discord installed", discordAbsent())
        withNative { native ->
            val handle = native.create(BuildConfig.DISCORD_APPLICATION_ID)
            try {
                native.publish(handle, "検証🌙".toByteArray(Charsets.UTF_8), "Izuna Tests".toByteArray(Charsets.UTF_8), true)
                native.clear(handle)
                repeat(10) { assertEquals(4, native.poll(handle)[0]); Thread.sleep(50) }
            } finally { native.destroy(handle) }
        }
    }
}
