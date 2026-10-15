package com.kadaiyare.izuna.widget

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.Player
import com.google.common.util.concurrent.ListenableFuture
import com.kadaiyare.izuna.playback.PLAYBACK_READY
import com.kadaiyare.izuna.playback.PLAYBACK_QUEUE_SIZE
import com.kadaiyare.izuna.playback.PlaybackService
import com.kadaiyare.izuna.playback.togglePlayback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class WidgetAction { Refresh, Toggle, Previous, Next }

/** A short-lived MediaController: background widgets never own an ExoPlayer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun controlFromWidget(context: Context, action: WidgetAction): WidgetState = withContext(Dispatchers.Main.immediate) {
    if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
        return@withContext WidgetState(status = WidgetStatus.Permission)
    val ready = CompletableDeferred<Unit>()
    fun checkReady(controller: MediaController, extras: Bundle = controller.sessionExtras) {
        // Session extras and the controller's timeline are separate callbacks. Wait for both.
        if (extras.getBoolean(PLAYBACK_READY) && controller.mediaItemCount == extras.getInt(PLAYBACK_QUEUE_SIZE, -1)) ready.complete(Unit)
    }
    val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { checkReady(player as MediaController) }
    }
    val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                checkReady(controller, extras)
            }
            override fun onDisconnected(controller: MediaController) {
                ready.completeExceptionally(IllegalStateException("Playback unavailable"))
            }
        }).buildAsync()
    var connected: MediaController? = null
    try {
        withTimeout(7_000) {
            val controller = future.awaitOnMain(context)
            connected = controller
            controller.addListener(playerListener)
            checkReady(controller)
            ready.await()
            // Permission may change while the service is restoring its checkpoint.
            if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                when (action) {
                    WidgetAction.Refresh -> Unit
                    WidgetAction.Toggle -> controller.togglePlayback()
                    WidgetAction.Previous -> if (controller.hasPreviousMediaItem()) controller.seekToPreviousMediaItem()
                    WidgetAction.Next -> if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
                }
            }
            WidgetState.from(context, controller)
        }
    } finally { connected?.removeListener(playerListener); MediaController.releaseFuture(future) }
}

private suspend fun <T> ListenableFuture<T>.awaitOnMain(context: Context): T = suspendCancellableCoroutine { continuation ->
    addListener({ if (continuation.isActive) continuation.resumeWith(runCatching { get() }) }, ContextCompat.getMainExecutor(context))
}
