package com.kadaiyare.izuna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kadaiyare.izuna.data.listenbrainz.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class ListenBrainzViewModel(application: Application) : AndroidViewModel(application) {
    private val queue = ListenBrainzQueue.get(application)
    private val repository = queue.auth
    val state = repository.state
    private val pending = AtomicBoolean(false)
    private val mutableWorking = MutableStateFlow(false)
    val working = mutableWorking.asStateFlow()
    private val localError = MutableStateFlow<String?>(null)
    val delivery = combine(state.map { it.destinationId }.distinctUntilChanged().flatMapLatest { destination ->
        if (destination == null) flowOf(ScrobbleStatus()) else queue.dao.counts(destination).map { rows ->
            fun count(status: String) = rows.filter { it.status == status }.sumOf { it.count }
            ScrobbleStatus(pending = count("pending"), sending = count("sending"), accepted = count("accepted"),
                rejected = count("rejected"), uncertain = count("uncertain"))
        }.catch { emit(ScrobbleStatus(error = "送信状態を読み込めません。")) }
    }, localError) { status, error -> status.copy(error = error ?: status.error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScrobbleStatus())

    fun connect(root: String, token: String) = command { repository.connect(root, token) }
    fun disconnect() = command { repository.disconnect() }
    fun scrobbling(enabled: Boolean) = command {
        repository.setEnabled(enabled)
        if (repository.destination() != null) ListenBrainzScheduler.enqueue(getApplication())
    }
    fun retry(uncertain: Boolean) = command {
        repository.withEnabled { connection -> queue.dao.retry(connection.id,
            if (uncertain) "uncertain" else "pending", System.currentTimeMillis()) }
        if (repository.destination() != null) ListenBrainzScheduler.enqueue(getApplication())
    }
    private fun command(action: suspend () -> Unit) {
        if (state.value.loading || state.value.busy || !pending.compareAndSet(false, true)) return
        mutableWorking.value = true; localError.value = null
        repository.scope.launch {
            try { action() }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { localError.value = "操作を完了できませんでした。もう一度お試しください。" }
            finally { pending.set(false); mutableWorking.value = false }
        }
    }
}
