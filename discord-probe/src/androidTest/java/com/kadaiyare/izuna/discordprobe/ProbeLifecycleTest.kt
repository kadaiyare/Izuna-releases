package com.kadaiyare.izuna.discordprobe

import android.content.pm.PackageManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProbeLifecycleTest {
    private fun requireEnabledPageSize() {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        assumeTrue("SDK initialization requires 4 KB or an explicit 16 KB diagnostic build",
            pageSize == 4096L || (pageSize == 16384L && BuildConfig.DIAGNOSE_16KB))
    }

    private fun waitReady(model: ProbeViewModel) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (!model.state.value.ready && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(model.state.value.message, model.state.value.ready)
    }

    @Test fun recreationRetainsInitializedNativeClient() {
        requireEnabledPageSize()
        ActivityScenario.launch(ProbeActivity::class.java).use { scenario ->
            lateinit var model: ProbeViewModel
            scenario.onActivity { model = ViewModelProvider(it)[ProbeViewModel::class.java] }
            waitReady(model)
            scenario.recreate()
            scenario.onActivity { assertSame(model, ViewModelProvider(it)[ProbeViewModel::class.java]) }
            assertEquals("1.10.19337", model.state.value.sdkVersion)
            assertFalse(model.state.value.busy)
            scenario.onActivity { model.clear() }
            val deadline = SystemClock.elapsedRealtime() + 2_000L
            while (!model.state.value.message.startsWith("消去を要求しました") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertTrue(model.state.value.message, model.state.value.message.startsWith("消去を要求しました"))
        }
        Thread.sleep(700) // Allow the worker's bounded teardown before the next SDK test.
    }

    @Test fun absentDiscordDoesNotStartAnRpcUpdate() {
        requireEnabledPageSize()
        val absent = try {
            InstrumentationRegistry.getInstrumentation().targetContext.packageManager
                .getPackageInfo("com.discord", PackageManager.PackageInfoFlags.of(0))
            false
        } catch (_: PackageManager.NameNotFoundException) { true }
        assumeTrue("Never publish with a real Discord client present", absent)
        ActivityScenario.launch(ProbeActivity::class.java).use { scenario ->
            lateinit var model: ProbeViewModel
            scenario.onActivity { model = ViewModelProvider(it)[ProbeViewModel::class.java] }
            waitReady(model)
            scenario.onActivity { model.publish(false, false) }
            assertFalse(model.state.value.busy)
            assertTrue(model.state.value.message.startsWith("公式Discordアプリをインストール"))
        }
        Thread.sleep(700)
    }
}
