package com.kadaiyare.izuna.data.playback
import com.kadaiyare.izuna.BuildConfig

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.kadaiyare.izuna.core.model.PlaybackSnapshot
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private object SnapshotSerializer : Serializer<PlaybackSnapshot> {
    override val defaultValue = PlaybackSnapshot()
    override suspend fun readFrom(input: InputStream): PlaybackSnapshot = try {
        PlaybackSnapshotCodec.read(input)
    } catch (error: InvalidPlaybackSnapshot) {
        throw CorruptionException("Invalid playback checkpoint", error)
    }
    override suspend fun writeTo(t: PlaybackSnapshot, output: OutputStream) = PlaybackSnapshotCodec.write(t, output)
}

/** Process singleton: writes survive service teardown and load is ordered after earlier writes. */
class PlaybackRepository private constructor(context: Context) {
    private sealed interface Request {
        data class Save(val value: PlaybackSnapshot) : Request
        data class Load(val result: CompletableDeferred<PlaybackSnapshot>) : Request
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = DataStoreFactory.create(
        serializer = SnapshotSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { PlaybackSnapshot() },
        scope = scope,
        produceFile = { context.noBackupFilesDir.resolve("playback.snapshot") },
    )
    private val requests = Channel<Request>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (request in requests) {
                try {
                    when (request) {
                        is Request.Save -> store.updateData { request.value }
                        is Request.Load -> request.result.complete(store.data.first())
                    }
                } catch (error: IOException) {
                    Log.w("IzunaPlayback", "Playback checkpoint unavailable", if (BuildConfig.DEBUG) error else null)
                    if (request is Request.Load) request.result.complete(PlaybackSnapshot())
                }
            }
        }
    }

    fun save(snapshot: PlaybackSnapshot) { requests.trySend(Request.Save(snapshot)) }
    suspend fun load(): PlaybackSnapshot {
        val result = CompletableDeferred<PlaybackSnapshot>()
        requests.send(Request.Load(result))
        return result.await()
    }

    companion object {
        @Volatile private var instance: PlaybackRepository? = null
        fun get(context: Context): PlaybackRepository = instance ?: synchronized(this) {
            instance ?: PlaybackRepository(context.applicationContext).also { instance = it }
        }
    }
}
