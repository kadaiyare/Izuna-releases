package com.kadaiyare.izuna

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.kadaiyare.izuna.core.model.ListeningSession
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.data.history.HistoryDatabase
import com.kadaiyare.izuna.data.history.HistoryRepository
import com.kadaiyare.izuna.ui.library.LibraryState
import com.kadaiyare.izuna.ui.settings.SettingsScreen
import com.kadaiyare.izuna.ui.settings.SettingsViewModel
import com.kadaiyare.izuna.ui.theme.IzunaTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The real dialog and ViewModel use an isolated database, never the user's stored history. */
class HistoryClearUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cancelKeepsHistoryAndConfirmClearsItWithCompletionFeedback() {
        val context = compose.activity
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        val job = SupervisorJob()
        val history = HistoryRepository.create(context, database, CoroutineScope(job + Dispatchers.IO))
        val model = SettingsViewModel(context.application as Application, history)
        try {
            val track = Track("ui-clear", "test://ui-clear", "Clear dialog fixture", "Izuna Tests", "Tests", 12000, null, 1, 1)
            val entry = ListeningSession("ui-clear", track, 1000, 2000, endedAtMs = 2000, listenedMs = 1000)
            runBlocking {
                history.awaitInitialized()
                history.save(entry)
                withTimeout(10000) { history.observeRecent().first { it.isNotEmpty() } }
            }
            compose.activity.setContent { IzunaTheme {
                SettingsScreen(model, LibraryState(), true, {}, {}, {}, {}, {})
            } }
            compose.waitUntil(10000) { model.state.value.settings != null && !model.state.value.saving }
            val action = compose.onNode(hasText("再生履歴を消去") and hasClickAction())
            action.performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("再生履歴を消去しますか？").assertIsDisplayed()
            compose.onNodeWithText("キャンセル").performClick()
            assertEquals(listOf(entry), runBlocking { history.observeRecent().first() })
            action.performClick()
            compose.onNodeWithText("消去する").assertIsEnabled().performClick()
            compose.waitUntil(10000) { runBlocking { history.observeRecent().first() }.isEmpty() }
            compose.onNodeWithText("再生履歴を消去しました。").performScrollTo().assertIsDisplayed()
        } finally { model.viewModelScope.cancel(); runBlocking { job.cancelAndJoin() }; database.close() }
    }

    @Test fun failedDeleteShowsErrorWithoutReportingSuccess() {
        val context = compose.activity
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        val job = SupervisorJob()
        val history = HistoryRepository.create(context, database, CoroutineScope(job + Dispatchers.IO))
        val model = SettingsViewModel(context.application as Application, history)
        try {
            runBlocking { history.awaitInitialized() }
            database.close()
            compose.activity.setContent { IzunaTheme {
                SettingsScreen(model, LibraryState(), true, {}, {}, {}, {}, {})
            } }
            compose.waitUntil(10000) { model.state.value.settings != null && !model.state.value.saving }
            compose.onNode(hasText("再生履歴を消去") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithText("消去する").assertIsEnabled().performClick()
            compose.waitUntil(10000) { model.state.value.error != null }
            compose.onNodeWithText("再生履歴を消去できませんでした。もう一度お試しください。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("再生履歴を消去しました。").assertDoesNotExist()
        } finally { model.viewModelScope.cancel(); runBlocking { job.cancelAndJoin() }; database.close() }
    }
}
