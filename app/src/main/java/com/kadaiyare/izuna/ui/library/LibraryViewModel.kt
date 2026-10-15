package com.kadaiyare.izuna.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.data.library.MediaStoreLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibraryState(
    val tracks: List<Track> = emptyList(),
    val scanning: Boolean = false,
    val error: String? = null,
    val permissionDenied: Boolean = false,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val library = MediaStoreLibrary(application.contentResolver)
    private val mutableState = MutableStateFlow(LibraryState())
    val state = mutableState.asStateFlow()
    private var scanJob: Job? = null

    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(scanning = true, error = null)
            try {
                mutableState.value = LibraryState(tracks = library.scan())
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecurityException) {
                mutableState.value = LibraryState(permissionDenied = true)
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    scanning = false, error = "ライブラリを読み取れません。再スキャンしてください。",
                )
            }
        }
    }

    fun permissionDenied() {
        scanJob?.cancel()
        mutableState.value = LibraryState(permissionDenied = true)
    }
}
