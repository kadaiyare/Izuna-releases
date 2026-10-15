package com.kadaiyare.izuna.widget

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Main-thread state with conflated artwork IO. A slow decode cannot restore an old track or play icon. */
internal class WidgetArtworkUpdates(
    scope: CoroutineScope,
    private val readArtwork: suspend (String) -> Bitmap?,
    private val render: (WidgetState, Bitmap?) -> Unit,
) {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var latest = WidgetState()
    private var artworkUri: String? = null
    private var artwork: Bitmap? = null

    init {
        scope.launch {
            for (change in changes) {
                val snapshot = latest
                render(snapshot, artwork.takeIf { artworkUri == snapshot.artworkUri })
                if (artworkUri == snapshot.artworkUri) continue
                val decoded = try { snapshot.artworkUri?.let { readArtwork(it) } }
                catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { null }
                if (latest != snapshot) continue
                artworkUri = snapshot.artworkUri
                artwork = decoded
                render(snapshot, decoded)
            }
        }
    }

    fun update(state: WidgetState, force: Boolean = false) {
        if (state == latest && !force) return
        latest = state
        changes.trySend(Unit)
    }
}
