package com.kadaiyare.izuna.data.playlist

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class Playlist(val id: String, val name: String, val trackUris: List<String>)

/** Store URI references only; the current library supplies metadata and availability. */
class PlaylistRepository private constructor(private val store: DataStore<Preferences>) {
    val data = store.data.map { decode(it[key] ?: "[]") }

    suspend fun create(name: String) {
        require(name.trim().isNotEmpty())
        update { it + Playlist(UUID.randomUUID().toString(), name.trim(), emptyList()) }
    }

    suspend fun rename(id: String, name: String) {
        require(name.trim().isNotEmpty())
        update { lists -> lists.map { if (it.id == id) it.copy(name = name.trim()) else it } }
    }

    suspend fun delete(id: String) = update { lists -> lists.filterNot { it.id == id } }

    suspend fun addTracks(id: String, uris: List<String>) = update { lists ->
        lists.map { if (it.id == id) it.copy(trackUris = (it.trackUris + uris).distinct()) else it }
    }

    suspend fun removeTrack(id: String, uri: String) = update { lists ->
        lists.map { if (it.id == id) it.copy(trackUris = it.trackUris.filterNot { track -> track == uri }) else it }
    }

    private suspend fun update(transform: (List<Playlist>) -> List<Playlist>) {
        store.edit { preferences ->
            preferences[key] = JSONArray().apply {
                transform(decode(preferences[key] ?: "[]")).forEach { list ->
                    put(JSONObject().put("id", list.id).put("name", list.name).put("uris", JSONArray(list.trackUris)))
                }
            }.toString()
        }
    }

    companion object {
        private val key = stringPreferencesKey("playlists")
        @Volatile private var instance: PlaylistRepository? = null
        fun get(context: Context): PlaylistRepository = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext.noBackupFilesDir.resolve("playlists.preferences_pb"),
                CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { instance = it }
        }
        internal fun create(file: File, scope: CoroutineScope) = PlaylistRepository(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))

        private fun decode(value: String): List<Playlist> {
            val array = JSONArray(value)
            return List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val uris = item.getJSONArray("uris")
                Playlist(item.getString("id"), item.getString("name"),
                    List(uris.length()) { uris.getString(it) })
            }
        }
    }
}
