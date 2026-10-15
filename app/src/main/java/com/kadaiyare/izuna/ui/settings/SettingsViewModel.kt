package com.kadaiyare.izuna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kadaiyare.izuna.data.settings.AppSettings
import com.kadaiyare.izuna.data.settings.SettingsRepository
import com.kadaiyare.izuna.data.history.HistoryRepository
import com.kadaiyare.izuna.data.discord.DiscordPresence
import com.kadaiyare.izuna.data.discord.DiscordPresenceState
import com.kadaiyare.izuna.data.discord.DiscordTitleFormat
import com.kadaiyare.izuna.data.discord.DiscordCompactDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SettingsState(val settings: AppSettings? = null, val saving: Boolean = false, val error: String? = null,
                         val discord: DiscordPresenceState = DiscordPresenceState(false, "確認中…"),
                         val historyMessage: String? = null)

class SettingsViewModel internal constructor(application: Application, private val history: HistoryRepository) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, HistoryRepository.get(application))
    private val repository = SettingsRepository.get(application)
    private val discord = DiscordPresence.get(application)
    private val refresh = MutableStateFlow(0)
    private val saving = MutableStateFlow(false)
    private val saveError = MutableStateFlow<String?>(null)
    private val historyMessage = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loaded = refresh.flatMapLatest {
        repository.data.map { SettingsState(settings = it) }.onStart { emit(SettingsState()) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(SettingsState(error = "設定を読み込めません。再読み込みしてください。"))
            }
    }
    val state = combine(loaded, saving, saveError, discord.state, historyMessage) { loaded, saving, error, presence, message ->
        loaded.copy(saving = saving, error = loaded.error ?: error, discord = presence, historyMessage = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun retry() { saveError.value = null; refresh.value++ }
    fun clearHistory() {
        if (saving.value) return
        saving.value = true
        saveError.value = null
        historyMessage.value = "再生履歴を消去しています…"
        viewModelScope.launch {
            try {
                history.clearHistory()
                historyMessage.value = "再生履歴を消去しました。"
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                historyMessage.value = null
                saveError.value = "再生履歴を消去できませんでした。もう一度お試しください。"
            } finally { saving.value = false }
        }
    }
    fun restoreQueue(enabled: Boolean) = save { repository.setRestoreQueue(enabled) }
    fun recordHistory(enabled: Boolean) = save { repository.setRecordHistory(enabled) }
    fun discordCoverLookup(enabled: Boolean) = save { repository.setDiscordCoverLookup(enabled) }
    fun discordShowAlbum(enabled: Boolean) = save { repository.setDiscordShowAlbum(enabled) }
    fun discordTitleFormat(format: DiscordTitleFormat) = save { repository.setDiscordTitleFormat(format) }
    fun discordCompactDisplay(display: DiscordCompactDisplay) = save { repository.setDiscordCompactDisplay(display) }
    fun onlineLyrics(enabled: Boolean) = save { repository.setOnlineLyrics(enabled) }
    fun groupArtistsByAlbumArtist(enabled: Boolean) = save { repository.setGroupArtistsByAlbumArtist(enabled) }
    fun discordSharing(enabled: Boolean) = save {
        if (!enabled) discord.setEnabled(false)
        if (enabled && !discord.state.value.available) return@save
        repository.setDiscordSharing(enabled)
    }

    private fun save(action: suspend () -> Unit) {
        if (saving.value) return
        saving.value = true
        saveError.value = null
        viewModelScope.launch {
            try { action() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                saveError.value = "設定を保存できません。端末の空き容量を確認してください。"
            } finally { saving.value = false }
        }
    }
}
