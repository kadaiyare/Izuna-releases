package com.kadaiyare.izuna.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kadaiyare.izuna.core.model.ListeningSession
import com.kadaiyare.izuna.data.history.HistoryRepository
import com.kadaiyare.izuna.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HistoryState(
    val entries: List<ListeningSession> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val recording: Boolean? = null,
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HistoryRepository.get(application)
    private val refresh = MutableStateFlow(0)
    private val recording = SettingsRepository.get(application).data.map { it.recordHistory }.catch { error ->
        if (error !is java.io.IOException) throw error
        emit(false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state = refresh.flatMapLatest {
        combine(repository.observeRecent(), repository.warning, recording) { entries, warning, recording ->
            HistoryState(entries = entries, error = warning, recording = recording)
        }.onStart { emit(HistoryState(loading = true)) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(HistoryState(error = "履歴を読み込めません。再読み込みしてください。"))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState(loading = true))

    fun retry() { refresh.value++ }
}
