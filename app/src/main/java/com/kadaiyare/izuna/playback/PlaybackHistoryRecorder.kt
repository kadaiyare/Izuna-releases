package com.kadaiyare.izuna.playback
import com.kadaiyare.izuna.BuildConfig

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.kadaiyare.izuna.core.model.ListeningTracker
import com.kadaiyare.izuna.data.history.HistoryRepository
import com.kadaiyare.izuna.data.settings.AppSettings
import com.kadaiyare.izuna.data.settings.SettingsRepository
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlaybackHistoryRecorder internal constructor(context: Context, private val player: Player,
    private val repository: HistoryRepository, settings: Flow<AppSettings>) {
    constructor(context: Context, player: Player) : this(context, player, HistoryRepository.get(context), SettingsRepository.get(context).data)
    private var tracker = ListeningTracker { UUID.randomUUID().toString() }
    private var historyGeneration = repository.generation.value
    private val nowPlaying = NowPlayingPublisher.create(context)
    private val compatibleNowPlaying = ListenBrainzNowPlayingPublisher.create(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var periodic: Job? = null
    private var boundary = false
    private var enabled = false
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { boundary = true }
        override fun onEvents(player: Player, events: Player.Events) {
            capture()
            updatePeriodic()
        }
    }

    init {
        player.addListener(listener)
        scope.launch {
            repository.generation.collect { generation ->
                if (generation != historyGeneration) { capture(); updatePeriodic() }
            }
        }
        scope.launch {
            settings.catch { error ->
                if (error !is java.io.IOException) throw error
                Log.w("IzunaHistory", "Unable to read history setting; recording disabled", if (BuildConfig.DEBUG) error else null)
                emit(AppSettings(restoreQueue = false, recordHistory = false))
            }.collect { settings ->
                if (enabled != settings.recordHistory) {
                    if (enabled) capture(closing = true)
                    enabled = settings.recordHistory
                    capture()
                    updatePeriodic()
                }
            }
        }
    }

    private fun updatePeriodic() {
        if (enabled && player.isPlaying && periodic == null) {
            periodic = scope.launch { while (true) { delay(5_000); capture() } }
        } else if (!enabled || !player.isPlaying) {
            periodic?.cancel()
            periodic = null
        }
    }

    private fun capture(closing: Boolean = false) {
        val generation = repository.generation.value
        if (generation != historyGeneration) {
            historyGeneration = generation
            tracker = ListeningTracker { UUID.randomUUID().toString() }
            boundary = false
            nowPlaying.update(null, false)
            compatibleNowPlaying.update(null, false)
        }
        if (!enabled) { boundary = false; nowPlaying.update(null, false); compatibleNowPlaying.update(null, false); return }
        val finished = closing || player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE || player.playerError != null
        val updates = tracker.update(player.currentMediaItem?.toStoredTrack(), player.isPlaying, SystemClock.elapsedRealtime(),
            System.currentTimeMillis(), boundary = boundary, finished = finished)
        updates.forEach { repository.save(it, historyGeneration) }
        nowPlaying.update(updates.lastOrNull(), player.isPlaying && !finished)
        compatibleNowPlaying.update(updates.lastOrNull(), player.isPlaying && !finished)
        boundary = false
    }

    fun close() {
        capture(closing = true)
        player.removeListener(listener)
        nowPlaying.close()
        compatibleNowPlaying.close()
        scope.cancel()
    }
}
