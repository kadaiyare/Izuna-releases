package com.kadaiyare.izuna.data.lastfm

import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal enum class NowPlayingResult { Accepted, Ignored, Failed, Reauthenticate }

internal fun interface NowPlayingApi {
    suspend fun submit(metadata: ScrobbleMetadata, durationMs: Long, session: StoredLastFmAuth.Connected): NowPlayingResult
}

/** A transient notification: failures are deliberately never queued or retried. */
internal class NowPlayingClient(
    private val config: LastFmConfig,
    private val transport: ScrobbleTransport = ScrobbleHttpsTransport(),
) : NowPlayingApi {
    override suspend fun submit(metadata: ScrobbleMetadata, durationMs: Long, session: StoredLastFmAuth.Connected): NowPlayingResult {
        require(metadata.valid() && session.apiKey == config.apiKey)
        val parameters = mutableMapOf("method" to "track.updateNowPlaying", "sk" to session.sessionKey,
            "track" to metadata.title, "artist" to metadata.artist)
        metadata.album?.let { parameters["album"] = it }
        if (durationMs >= 1000) parameters["duration"] = (durationMs / 1000).toString()
        return try {
            val response = transport.post(config.signed(parameters) + ("format" to "json"))
            val json = JSONObject(response.body)
            if (json.has("error")) return if (json.getInt("error") == 9) NowPlayingResult.Reauthenticate else NowPlayingResult.Failed
            if (response.status != 200) return NowPlayingResult.Failed
            // Corrections from this response must never replace the original Scrobble tags.
            when (json.getJSONObject("nowplaying").getJSONObject("ignoredMessage").getInt("code")) {
                0 -> NowPlayingResult.Accepted
                in 1..5 -> NowPlayingResult.Ignored
                else -> NowPlayingResult.Failed
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) { NowPlayingResult.Failed }
    }
}
