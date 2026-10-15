package com.kadaiyare.izuna.discordprobe

import android.system.Os
import android.system.OsConstants
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run separately from the SDK tests: this proves the ordinary build never loads it on 16 KB. */
@RunWith(AndroidJUnit4::class)
class ProbePageSizeGuardTest {
    @Test fun ordinaryBuildRejects16kbBeforeLoadingSdk() {
        assumeTrue("This guard is for the ordinary build on a 16 KB device",
            Os.sysconf(OsConstants._SC_PAGESIZE) == 16384L && !BuildConfig.DIAGNOSE_16KB)
        ActivityScenario.launch(ProbeActivity::class.java).use { scenario ->
            scenario.onActivity {
                val state = ViewModelProvider(it)[ProbeViewModel::class.java].state.value
                assertFalse(state.ready)
                assertNull(state.sdkVersion)
                assertTrue(state.message.contains("4 KB端末"))
                assertFalse(File("/proc/self/maps").readText().contains("libdiscord_partner_sdk.so"))
            }
        }
    }
}
