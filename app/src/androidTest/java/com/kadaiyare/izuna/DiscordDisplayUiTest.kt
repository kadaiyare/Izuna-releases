package com.kadaiyare.izuna

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kadaiyare.izuna.data.settings.AppSettings
import com.kadaiyare.izuna.data.settings.SettingsRepository
import com.kadaiyare.izuna.data.discord.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.kadaiyare.izuna.ui.settings.*
import com.kadaiyare.izuna.ui.theme.IzunaTheme
import org.junit.Rule
import org.junit.Test

/** Isolated display choices; does not change saved sharing or publish real RPC. */
class DiscordDisplayUiTest {
    @get:Rule(order = 0) val audioPermission = AudioPermissionRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsNavigationSavesChoicesAndReturnsToSettingsAfterRecreation() {
        val repository = SettingsRepository.get(compose.activity)
        val original = runBlocking { repository.data.first() }
        try {
            runBlocking { repository.setDiscordSharing(false) }
            compose.onNodeWithContentDescription("設定").performClick()
            compose.onNodeWithTag("settingsList").performScrollToNode(hasText("Discordの表示設定"))
            compose.onNodeWithText("Discordの表示設定").performScrollTo().performClick()
            val albumRow = compose.onNode(hasText("アルバム名を表示") and isToggleable())
            albumRow.performScrollTo()
            if (!original.discordDisplay.showAlbum) albumRow.performClick()
            compose.waitUntil(15_000) { runBlocking { repository.data.first().discordDisplay.showAlbum } }
            chooseSavedOption("曲名 · アーティスト")
            compose.waitUntil(15_000) { runBlocking { repository.data.first().discordDisplay.titleFormat == DiscordTitleFormat.SONG_ARTIST } }
            chooseSavedOption("曲のタイトル行")
            compose.waitUntil(15_000) { runBlocking { repository.data.first().discordDisplay.compactDisplay == DiscordCompactDisplay.TITLE } }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("discordDisplayList").performScrollToNode(hasText("曲のタイトル行"))
            compose.onNodeWithText("曲のタイトル行").performScrollTo().assertIsSelected()
            compose.onNodeWithContentDescription("戻る").performClick()
            compose.onNodeWithTag("settingsList").assertIsDisplayed()
            compose.runOnIdle { org.junit.Assert.assertFalse(runBlocking { repository.data.first().discordSharing }) }
        } finally { runBlocking {
            repository.setDiscordShowAlbum(original.discordDisplay.showAlbum)
            repository.setDiscordTitleFormat(original.discordDisplay.titleFormat)
            repository.setDiscordCompactDisplay(original.discordDisplay.compactDisplay)
            repository.setDiscordSharing(original.discordSharing)
        } }
    }

    private fun chooseSavedOption(label: String) {
        val choice = hasText(label) and isSelectable()
        compose.onNode(choice).performScrollTo()
        // A durable DataStore write can finish before the ViewModel releases its saving guard.
        compose.waitUntil(15_000) { compose.onAllNodes(choice and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(choice).performClick()
    }

    @Test fun choicesUpdatePreviewAndReturnWithoutEnablingSharing() {
        val options = mutableStateOf(AppSettings())
        var returned = false
        compose.activity.setContent { IzunaTheme {
            DiscordDisplayContent(SettingsState(settings = options.value), { returned = true },
                { options.value = options.value.copy(discordDisplay = options.value.discordDisplay.copy(showAlbum = it)) },
                { options.value = options.value.copy(discordDisplay = options.value.discordDisplay.copy(titleFormat = it)) },
                { options.value = options.value.copy(discordDisplay = options.value.discordDisplay.copy(compactDisplay = it)) })
        } }
        compose.onNodeWithTag("discordPreviewCompact").assertTextEquals("Izuna")
        compose.onNodeWithText("アーティスト · 曲名").performScrollTo().performClick()
        compose.onNodeWithText("アルバム名を表示").performScrollTo().performClick()
        compose.onNodeWithText("曲のタイトル行").performScrollTo().performClick()
        compose.onNodeWithTag("discordPreviewTitle").performScrollTo().assertTextEquals("サンプルのアーティスト · サンプルの曲")
        compose.onNodeWithTag("discordPreviewSubtitle").assertTextEquals("サンプルのアーティスト · サンプルのアルバム")
        compose.onNodeWithTag("discordPreviewCompact").assertTextEquals("サンプルのアーティスト · サンプルの曲")
        compose.runOnIdle { org.junit.Assert.assertFalse(options.value.discordSharing) }
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(returned) }
    }
}
