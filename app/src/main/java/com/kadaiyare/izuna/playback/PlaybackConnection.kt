package com.kadaiyare.izuna.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.kadaiyare.izuna.core.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val connected: Boolean = false,
    val current: MediaItem? = null,
    val queue: List<MediaItem> = emptyList(),
    val upcomingIndices: List<Int> = emptyList(),
    val currentIndex: Int = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val canSeek: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
)

/** A controller projection of the service. All calls run on the main thread. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackConnection(private val context: Context) {
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private val mutableState = MutableStateFlow(PlaybackState())
    val state = mutableState.asStateFlow()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val pending = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    mutableState.value = PlaybackState(error = "再生サービスとの接続が切れました。画面を開き直してください。")
                }
            })
            .buildAsync()
        future = pending
        pending.addListener({
            // An Activity can stop before asynchronous binding completes.
            if (future !== pending) return@addListener
            try {
                controller = pending.get().also { it.addListener(listener) }
                refresh()
            } catch (error: Exception) {
                future = null
                mutableState.value = PlaybackState(error = "再生サービスに接続できません。画面を開き直してください。")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        controller?.removeListener(listener)
        controller = null
        future?.let(MediaController::releaseFuture)
        future = null
        mutableState.value = PlaybackState()
    }

    fun refresh() {
        val player = controller ?: return
        val error = player.playerError?.let {
            when (it.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "ファイルが見つかりません。ライブラリを再スキャンしてください。"
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "音源へのアクセスが拒否されました。権限を確認してください。"
                else -> "この音源を再生できません。ファイルの破損や対応形式を確認してください。"
            }
        }
        mutableState.value = PlaybackState(
            connected = true,
            current = player.currentMediaItem,
            queue = (0 until player.mediaItemCount).map(player::getMediaItemAt),
            upcomingIndices = buildList {
                val visited = mutableSetOf(player.currentMediaItemIndex)
                var index = player.currentMediaItemIndex
                if (index >= 0 && !player.currentTimeline.isEmpty) {
                    do {
                        index = player.currentTimeline.getNextWindowIndex(index,
                            if (player.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF,
                            player.shuffleModeEnabled)
                        if (index != C.INDEX_UNSET && visited.add(index)) add(index) else break
                    } while (size < player.mediaItemCount)
                }
            },
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0)
                ?: player.mediaMetadata.durationMs ?: 0,
            canSeek = player.isCurrentMediaItemSeekable,
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem(),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            error = error,
        )
    }

    fun play(tracks: List<Track>, index: Int, loop: Boolean = false) {
        val player = controller ?: return
        if (index !in tracks.indices) return
        if (loop) player.repeatMode = Player.REPEAT_MODE_ALL
        player.setMediaItems(tracks.map(Track::toMediaItem), index, 0)
        player.prepare()
        player.play()
    }

    /** Replace the queue with the current album without restarting the current song. */
    fun loopAlbum(tracks: List<Track>) {
        val player = controller ?: return
        val index = tracks.indexOfFirst { it.id == player.currentMediaItem?.mediaId }
        if (index < 0) return
        val position = player.currentPosition.coerceAtLeast(0)
        val playing = player.playWhenReady
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.setMediaItems(tracks.map(Track::toMediaItem), index, position)
        player.prepare()
        player.playWhenReady = playing
    }

    fun refreshPosition() {
        val player = controller ?: return
        mutableState.value = mutableState.value.copy(positionMs = player.currentPosition.coerceAtLeast(0))
    }

    fun toggle() {
        controller?.togglePlayback()
    }

    fun previous() { controller?.seekToPreviousMediaItem() }
    fun next() { controller?.seekToNextMediaItem() }
    fun seek(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0)) }
    fun toggleShuffle() { controller?.run { shuffleModeEnabled = !shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.run {
            repeatMode = when (repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
    fun stop() { controller?.run { pause(); stop(); seekTo(0) } }
    fun selectQueueItem(index: Int) {
        controller?.run {
            if (index !in 0 until mediaItemCount) return
            seekTo(index, 0)
            prepare()
            play()
        }
    }
}
