package com.kadaiyare.izuna.ui.player

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kadaiyare.izuna.core.model.*
import com.kadaiyare.izuna.data.audio.readFlacScrobbleTags
import com.kadaiyare.izuna.data.lyrics.*
import com.kadaiyare.izuna.data.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class LyricsState(val uri: String? = null, val loading: Boolean = false,
    val online: Boolean = false, val document: LyricsDocument? = null, val cached: Boolean = false,
    val adjustmentMs: Long = 0, val message: String? = null, val error: Boolean = false,
    val saving: Boolean = false)

internal class LyricsViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: LyricsRepository = LyricsRepository.get(application),
    private val settings: SettingsRepository = SettingsRepository.get(application),
    private val queryReader: suspend (String, Long) -> LyricsQuery? = { uri, duration ->
        withContext(Dispatchers.IO) {
            application.contentResolver.openInputStream(uri.toUri())?.use(::readFlacScrobbleTags)
                ?.let { LyricsQuery(it.title, it.artist, it.album, (duration / 1000).toInt()) }
        }
    },
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(LyricsState())
    val state = mutableState.asStateFlow()
    private data class Selection(val uri: String, val duration: Long)
    private var selection: Selection? = null
    val isVisible: Boolean get() = selection != null
    private var generation = 0
    private var loading: Job? = null
    private var onlineLoaded = false
    init {
        viewModelScope.launch {
            settings.data.map { it.onlineLyrics }.distinctUntilChanged().catch { emit(false) }.collect { enabled ->
                val changed = !onlineLoaded || mutableState.value.online != enabled
                onlineLoaded = true
                mutableState.update { it.copy(online = enabled) }
                if (changed) selection?.let { load(it) }
            }
        }
    }

    fun show(uri: String, durationMs: Long) {
        val selected = Selection(uri, durationMs / 1000 * 1000)
        if (selection == selected) return
        selection = selected
        load(selected)
    }
    fun hide() { selection = null; generation++; loading?.cancel(); mutableState.update { it.copy(loading = false) } }
    fun retry() { selection?.let { load(it, refresh = true) } }
    fun enableOnline() {
        viewModelScope.launch {
            try { settings.setOnlineLyrics(true) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(message = "設定を保存できませんでした。", error = true) }
            }
        }
    }
    fun adjust(delta: Long?, reset: Boolean = false) {
        val selected = selection ?: return
        if (mutableState.value.saving) return
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val offset = repository.adjust(selected.uri, delta, reset)
                if (selection == selected) mutableState.update { it.copy(adjustmentMs = offset, saving = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (selection == selected) mutableState.update { it.copy(saving = false,
                    message = "タイミング補正を保存できませんでした。", error = true) }
            }
        }
    }
    private fun load(selected: Selection, refresh: Boolean = false) {
        loading?.cancel()
        val version = ++generation
        val online = mutableState.value.online
        mutableState.value = LyricsState(uri = selected.uri, online = online, loading = true)
        loading = viewModelScope.launch {
            try {
                val offset = repository.adjustment(selected.uri)
                val query = queryReader(selected.uri, selected.duration)?.takeIf { it.valid() }
                val result = if (query != null) repository.load(query, online && onlineLoaded, refresh) else LyricsLoadResult(null)
                ensureActive()
                if (version != generation) return@launch
                mutableState.value = LyricsState(selected.uri, online = online, document = result.document,
                    cached = result.cached, adjustmentMs = offset, message = result.warning ?: when {
                        selected.duration <= 0 -> "再生時間を確認中…"
                        selected.duration > 3_600_000 -> "1時間を超える曲のオンライン検索には未対応です。"
                        query == null -> "FLACの曲名・アーティストを読み取れません。"
                        result.document != null -> null
                        !online -> "保存済みの歌詞がありません。"
                        else -> "この音源に一致する歌詞が見つかりませんでした。"
                    })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == generation) mutableState.update { it.copy(loading = false, error = true,
                    message = if (error is LyricsRateLimited) "しばらく待ってから再取得してください（約${error.seconds}秒）。"
                    else "歌詞を取得できませんでした。通信や音源へのアクセスを確認してください。") }
            }
        }
    }
}
