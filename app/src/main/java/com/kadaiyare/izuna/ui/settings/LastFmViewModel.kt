package com.kadaiyare.izuna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kadaiyare.izuna.data.history.HistoryRepository
import com.kadaiyare.izuna.data.lastfm.LastFmAuthRepository
import com.kadaiyare.izuna.data.lastfm.ScrobbleScheduler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class ScrobbleStatus(val pending: Int = 0, val sending: Int = 0, val accepted: Int = 0,
    val ignored: Int = 0, val rejected: Int = 0, val uncertain: Int = 0, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class LastFmViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LastFmAuthRepository.get(application)
    val state = repository.state
    private val browserUrls = Channel<String>(Channel.BUFFERED)
    val browserRequests = browserUrls.receiveAsFlow()
    private val commandPending = AtomicBoolean(false)
    private val mutableWorking = MutableStateFlow(false)
    val working = mutableWorking.asStateFlow()
    private val localError = MutableStateFlow<String?>(null)
    private val history = HistoryRepository.get(application)
    val delivery = combine(state.map { it.accountName }.distinctUntilChanged().flatMapLatest { account ->
        if (account == null) flowOf(ScrobbleStatus()) else history.observeJobCounts(account).map { counts ->
            fun count(prefix: String) = counts.filter { it.status == prefix || it.status.startsWith("$prefix:") }.sumOf { it.count }
            ScrobbleStatus(count("pending"), count("sending"), count("accepted"), count("ignored"), count("rejected"), count("uncertain"))
        }.catch { emit(ScrobbleStatus(error = "送信状態を読み込めません。")) }
    }, localError) { status, error -> status.copy(error = error ?: status.error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScrobbleStatus())

    fun connect() {
        command { repository.begin()?.let { browserUrls.send(it) } }
    }

    fun confirm() {
        command { repository.confirm() }
    }

    fun disconnect() {
        command { repository.disconnect() }
    }

    fun scrobbling(enabled: Boolean) = command { repository.setScrobbling(enabled) }

    fun retry(uncertain: Boolean) = command {
        repository.withEnabledSession { session ->
            if (uncertain) history.dao.retryUncertain(session.accountName, System.currentTimeMillis())
            else history.dao.retryPending(session.accountName, System.currentTimeMillis())
        }
        if (repository.destination() != null) ScrobbleScheduler.enqueue(getApplication())
    }

    private fun command(action: suspend () -> Unit) {
        if (state.value.busy || state.value.loading || !commandPending.compareAndSet(false, true)) return
        mutableWorking.value = true
        localError.value = null
        repository.scope.launch {
            try { action() }
            catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
            catch (_: Exception) { localError.value = "操作を完了できませんでした。もう一度お試しください。" }
            finally { commandPending.set(false); mutableWorking.value = false }
        }
    }

    fun browserUnavailable() = repository.browserUnavailable()
}
