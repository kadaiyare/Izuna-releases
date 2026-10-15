package com.kadaiyare.izuna.playback
import com.kadaiyare.izuna.BuildConfig

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.media3.common.Player
import com.kadaiyare.izuna.core.model.PlaybackSnapshot
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.core.model.withAvailableTracks
import com.kadaiyare.izuna.data.library.MediaStoreLibrary
import com.kadaiyare.izuna.data.playback.PlaybackRepository
import com.kadaiyare.izuna.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Observes the service player; disk work never runs on its application thread. */
class PlaybackCheckpoint(private val context: Context, private val player: Player) {
    internal val ready = CompletableDeferred<Unit>()
    private val repository = PlaybackRepository.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var periodic: Job? = null
    private var loading = true
    private var changedDuringLoad = false
    private var tracks = emptyList<Track>()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (loading && events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                changedDuringLoad = true
            }
            if (!loading || changedDuringLoad) checkpoint(events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            periodic?.cancel()
            periodic = if (isPlaying) scope.launch {
                while (true) { delay(5_000); checkpoint() }
            } else null
        }
    }

    init {
        player.addListener(listener)
        scope.launch {
            try {
                val restore = try {
                    SettingsRepository.get(context).data.first().restoreQueue
                } catch (error: java.io.IOException) {
                    Log.w("IzunaPlayback", "Unable to read restoration setting", if (BuildConfig.DEBUG) error else null)
                    false
                }
                if (!restore) {
                    loading = false
                    if (changedDuringLoad) checkpoint(refreshQueue = true)
                    return@launch
                }
                val saved = repository.load()
                val canRead = context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
                val restored = if (saved.tracks.isEmpty() || !canRead) {
                    saved
                } else {
                    try {
                        saved.withAvailableTracks(MediaStoreLibrary(context.contentResolver).scan().associateBy { it.id })
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Log.w("IzunaPlayback", "Unable to check restored media", if (BuildConfig.DEBUG) error else null)
                        saved
                    }
                }
                // A new queue chosen while IO was pending always wins over the old checkpoint.
                if (!changedDuringLoad && player.mediaItemCount == 0) {
                    player.playWhenReady = false
                    player.shuffleModeEnabled = restored.shuffle
                    player.repeatMode = restored.repeatMode
                    if (restored.tracks.isNotEmpty()) {
                        player.setMediaItems(restored.tracks.map(Track::toMediaItem), restored.currentIndex, restored.positionMs)
                        if (canRead) player.prepare()
                    }
                }
                loading = false
                checkpoint(refreshQueue = true)
            } finally { ready.complete(Unit) }
        }
    }

    private fun checkpoint(refreshQueue: Boolean = false) {
        if (loading && !changedDuringLoad) return
        if (refreshQueue || tracks.size != player.mediaItemCount) {
            tracks = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toStoredTrack() }
        }
        repository.save(PlaybackSnapshot(
            tracks = tracks,
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        ))
    }

    fun close() {
        checkpoint(refreshQueue = true)
        player.removeListener(listener)
        scope.cancel()
    }
}
