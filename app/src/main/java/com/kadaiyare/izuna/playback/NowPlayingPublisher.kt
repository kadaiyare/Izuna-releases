package com.kadaiyare.izuna.playback

import android.content.Context
import android.os.SystemClock
import androidx.core.net.toUri
import com.kadaiyare.izuna.BuildConfig
import com.kadaiyare.izuna.core.model.ListeningSession
import com.kadaiyare.izuna.core.model.ScrobbleDestination
import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import com.kadaiyare.izuna.data.audio.readFlacScrobbleTags
import com.kadaiyare.izuna.data.lastfm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** All state and updates belong to the Player's main thread. IO never reads Player directly. */
internal class NowPlayingPublisher(
    private val auth: LastFmAuthRepository,
    private val api: NowPlayingApi,
    private val scope: CoroutineScope,
    private val readTags: suspend (String) -> ScrobbleMetadata?,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class Candidate(val session: ListeningSession, val destination: ScrobbleDestination?, val createdAt: Long, val revision: Long)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var current: Candidate? = null
    private var playing = false
    private var attempted: String? = null
    private var revision = 0L

    init {
        scope.launch {
            for (change in changes) {
                val candidate = current ?: continue
                if (attempted == candidate.session.id) continue
                attempted = candidate.session.id
                if (!fresh(candidate)) continue
                try {
                    val metadata = readTags(candidate.session.track.uri)?.takeIf { it.valid() } ?: continue
                    auth.withEnabledSession { connected ->
                        // Tag IO or another Last.fm request may have taken time. Recheck immediately before POST.
                        if (!fresh(candidate) || connected.accountName != candidate.destination?.accountName ||
                            connected.enabledAtMs != candidate.destination.enabledAtMs) return@withEnabledSession
                        if (api.submit(metadata, candidate.session.track.durationMs, connected) == NowPlayingResult.Reauthenticate)
                            throw LastFmInvalidSession()
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) { /* A notification failure must not stop playback or retry. */ }
            }
        }
    }

    fun update(session: ListeningSession?, isPlaying: Boolean) {
        val active = session?.takeIf { it.endedAtMs == null }
        if (playing && !isPlaying) revision++
        if (current?.session?.id != active?.id) {
            current = active?.let { Candidate(it, auth.destination(), elapsed(), revision) }
        }
        playing = isPlaying && active != null
        changes.trySend(Unit)
    }

    private fun fresh(candidate: Candidate): Boolean {
        val destination = candidate.destination ?: return false
        val age = elapsed() - candidate.createdAt
        return current === candidate && candidate.revision == revision && playing && candidate.session.startedAtMs >= destination.enabledAtMs &&
            auth.destination() == destination && age in 0..30_000
    }

    fun close() { current = null; playing = false; changes.close(); scope.cancel() }

    companion object {
        fun create(context: Context): NowPlayingPublisher {
            val app = context.applicationContext
            return NowPlayingPublisher(LastFmAuthRepository.get(app),
                NowPlayingClient(LastFmConfig(BuildConfig.LAST_FM_API_KEY, BuildConfig.LAST_FM_SHARED_SECRET)),
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                { uri -> withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri.toUri())?.use(::readFlacScrobbleTags) } })
        }
    }
}
