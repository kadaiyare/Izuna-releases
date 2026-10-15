package com.kadaiyare.izuna.ui.playlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kadaiyare.izuna.data.playlist.Playlist
import com.kadaiyare.izuna.data.playlist.PlaylistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaylistState(val lists: List<Playlist> = emptyList(), val loading: Boolean = true, val error: String? = null)

class PlaylistViewModel internal constructor(application: Application, private val repository: PlaylistRepository) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, PlaylistRepository.get(application))
    private val mutableState = MutableStateFlow(PlaylistState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                repository.data.collect { mutableState.value = PlaylistState(lists = it, loading = false) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = PlaylistState(loading = false, error = "プレイリストを読み込めません。") }
        }
    }
    fun create(name: String) = edit { repository.create(name) }
    fun rename(id: String, name: String) = edit { repository.rename(id, name) }
    fun delete(id: String) = edit { repository.delete(id) }
    fun addTracks(id: String, uris: List<String>) = edit { repository.addTracks(id, uris) }
    fun removeTrack(id: String, uri: String) = edit { repository.removeTrack(id, uri) }
    private fun edit(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action(); mutableState.value = mutableState.value.copy(error = null) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "変更を保存できません。もう一度お試しください。") }
        }
    }
}
