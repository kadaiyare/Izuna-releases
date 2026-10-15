package com.kadaiyare.izuna.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.kadaiyare.izuna.MainActivity
import com.kadaiyare.izuna.widget.PlaybackWidgetRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal const val PLAYBACK_READY = "com.kadaiyare.izuna.playback.READY"
internal const val PLAYBACK_QUEUE_SIZE = "com.kadaiyare.izuna.playback.QUEUE_SIZE"

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var checkpoint: PlaybackCheckpoint? = null
    private var history: PlaybackHistoryRecorder? = null
    private var widgets: PlaybackWidgetRecorder? = null
    private var discord: DiscordPlaybackRecorder? = null
    private var readinessListener: Player.Listener? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(PlaybackControllerPolicy).build()
        checkpoint = PlaybackCheckpoint(this, player)
        history = PlaybackHistoryRecorder(this, player)
        widgets = PlaybackWidgetRecorder(this, player)
        discord = DiscordPlaybackRecorder(this, player)
        readinessListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (checkpoint?.ready?.isCompleted == true) publishReadiness(player)
            }
        }.also(player::addListener)
        scope.launch {
            checkpoint?.ready?.await()
            publishReadiness(player)
        }
    }

    private fun publishReadiness(player: Player) {
        val current = session ?: return
        if (current.sessionExtras.getBoolean(PLAYBACK_READY) && current.sessionExtras.getInt(PLAYBACK_QUEUE_SIZE, -1) == player.mediaItemCount) return
        current.setSessionExtras(Bundle().apply { putBoolean(PLAYBACK_READY, true); putInt(PLAYBACK_QUEUE_SIZE, player.mediaItemCount) })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (PlaybackControllerPolicy.allows(controllerInfo)) session else null

    override fun onDestroy() {
        scope.cancel()
        readinessListener?.let { session?.player?.removeListener(it) }
        readinessListener = null
        widgets?.close()
        widgets = null
        discord?.close()
        discord = null
        history?.close()
        history = null
        checkpoint?.close()
        checkpoint = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
