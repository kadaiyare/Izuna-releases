package com.kadaiyare.izuna

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** No external requests: an HTTP URL is rejected before token validation. */
class ListenBrainzUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun settingsEntryOpensCustomServerFormAndRejectsHttpLocally() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("libraryTab-0").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithContentDescription("設定").performClick()
        val serverEntry = hasText("ListenBrainz互換サーバー") and hasClickAction()
        compose.onNodeWithTag("settingsList").performScrollToNode(serverEntry)
        compose.onNode(serverEntry).performClick()
        compose.onNodeWithText("API URL").performTextReplacement("http://example.test/1")
        compose.onNodeWithText("ユーザートークン").performTextInput("synthetic-ui-token")
        compose.onNodeWithText("接続を確認して保存").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("HTTPSのAPI URLを入力してください。末尾は /1 まで指定できます。").fetchSemanticsNodes(false).isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("API URL").assertTextContains("http://example.test/1")
        compose.onNodeWithText("ユーザートークン").assertTextEquals("ユーザートークン", "")
        compose.onNodeWithText("接続を確認して保存").assertIsNotEnabled()
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("captureListenBrainzScreens") == "true") {
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            compose.waitForIdle()
            Thread.sleep(1000)
            val focus = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("dumpsys window")).bufferedReader().use { reader ->
                reader.lineSequence().firstOrNull { it.trim().startsWith("mCurrentFocus=") }
            }
            org.junit.Assert.assertTrue("App must be foreground without a system dialog",
                focus?.contains("${instrumentation.targetContext.packageName}/") == true)
            instrumentation.uiAutomation.takeScreenshot().let { screenshot ->
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "listenbrainz-settings.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        }
    }
}
