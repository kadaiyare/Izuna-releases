package com.kadaiyare.izuna.playback

import android.os.Process
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** A package name alone is not authorization to inspect or control the user's queue. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackControllerPolicy : MediaSession.Callback {
    fun allows(controller: MediaSession.ControllerInfo) = controller.isTrusted || controller.uid == Process.myUid()

    override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> =
        if (allows(controller)) Futures.immediateFuture(MediaSession.ConnectionResult.AcceptedResultBuilder(session).build())
        else Futures.immediateFuture(MediaSession.ConnectionResult.reject())
}
