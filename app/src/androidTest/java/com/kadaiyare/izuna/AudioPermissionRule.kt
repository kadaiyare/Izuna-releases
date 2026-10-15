package com.kadaiyare.izuna

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Library navigation tests need permission before MainActivity runs its initial scan. */
class AudioPermissionRule : ExternalResource() {
    override fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName, Manifest.permission.READ_MEDIA_AUDIO)
    }
}
