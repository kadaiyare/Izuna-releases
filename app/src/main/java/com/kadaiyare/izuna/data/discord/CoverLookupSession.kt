package com.kadaiyare.izuna.data.discord

import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import kotlinx.coroutines.*

/** Main-thread session. Text is immediate; an image can only enrich the current playback. */
internal class CoverLookupSession(
    private val scope: CoroutineScope,
    private val provider: CoverArtProvider,
    private val publish: (PresenceSong) -> Unit,
) {
    private var revision = 0L
    private var lookup: Job? = null

    fun cancel() { revision++; lookup?.cancel(); lookup = null }

    fun start(tags: ScrobbleMetadata, search: Boolean, current: () -> PresenceSong?) {
        cancel()
        val request = revision
        val song = current() ?: return
        publish(song.copy(imageUrl = null))
        if (!search) return
        lookup = scope.launch {
            val url = try { provider.find(tags) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
            if (url == null || request != revision) return@launch
            current()?.let { publish(it.copy(imageUrl = url)) }
        }
    }
}
