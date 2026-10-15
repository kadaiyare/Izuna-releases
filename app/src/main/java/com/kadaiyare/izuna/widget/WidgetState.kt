package com.kadaiyare.izuna.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.media3.common.Player

internal enum class WidgetStatus { Idle, Playing, Paused, Buffering, Ended, Error, Permission, Unavailable }

/** A render-only projection. It never persists playing or owns a playback queue. */
internal data class WidgetState(
    val title: String? = null,
    val artist: String? = null,
    val artworkUri: String? = null,
    val hasTrack: Boolean = false,
    val pauseAction: Boolean = false,
    val previous: Boolean = false,
    val next: Boolean = false,
    val status: WidgetStatus = WidgetStatus.Idle,
) {
    val canControl: Boolean get() = hasTrack && status !in setOf(WidgetStatus.Permission, WidgetStatus.Unavailable)

    fun stopped() = copy(pauseAction = false, status = when (status) {
        WidgetStatus.Permission, WidgetStatus.Unavailable, WidgetStatus.Error, WidgetStatus.Ended -> status
        else -> if (hasTrack) WidgetStatus.Paused else WidgetStatus.Idle
    })

    companion object {
        fun from(context: Context, player: Player): WidgetState {
            val allowed = context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            val hasTrack = player.currentMediaItem != null
            val metadata = player.mediaMetadata
            return WidgetState(metadata.title?.toString(), metadata.artist?.toString(), metadata.artworkUri?.toString(), hasTrack,
                pauseAction = allowed && player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playerError == null,
                previous = allowed && player.hasPreviousMediaItem(), next = allowed && player.hasNextMediaItem(),
                status = when {
                    !allowed -> WidgetStatus.Permission
                    !hasTrack -> WidgetStatus.Idle
                    player.playerError != null -> WidgetStatus.Error
                    player.isPlaying -> WidgetStatus.Playing
                    player.playWhenReady && player.playbackState == Player.STATE_BUFFERING -> WidgetStatus.Buffering
                    player.playbackState == Player.STATE_ENDED -> WidgetStatus.Ended
                    else -> WidgetStatus.Paused
                })
        }
    }
}
