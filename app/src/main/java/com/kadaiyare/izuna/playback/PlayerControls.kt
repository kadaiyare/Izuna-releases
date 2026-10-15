package com.kadaiyare.izuna.playback

import androidx.media3.common.Player

/** Shared by the app and widget controllers, including paused buffering and ended playback. */
internal fun Player.togglePlayback() {
    if (mediaItemCount == 0) return
    if (playWhenReady && playbackState != Player.STATE_ENDED && playerError == null) {
        pause()
    } else {
        if (playbackState == Player.STATE_ENDED) seekTo(0)
        if (playbackState == Player.STATE_IDLE || playerError != null) prepare()
        play()
    }
}
