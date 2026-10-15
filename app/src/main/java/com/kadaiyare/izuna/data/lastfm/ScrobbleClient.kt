package com.kadaiyare.izuna.data.lastfm

import com.kadaiyare.izuna.data.history.ScrobbleJob
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal sealed class ScrobbleResult {
    data object Accepted : ScrobbleResult()
    data class Ignored(val code: Int) : ScrobbleResult()
    data object Retry : ScrobbleResult()
    data object Reauthenticate : ScrobbleResult()
    data class Rejected(val code: Int) : ScrobbleResult()
    data object Uncertain : ScrobbleResult()
}

internal fun interface ScrobbleApi {
    suspend fun submit(job: ScrobbleJob, session: StoredLastFmAuth.Connected): ScrobbleResult
}

internal fun interface ScrobbleTransport {
    suspend fun post(parameters: Map<String, String>): LastFmResponse
}

internal class ScrobbleNetworkException(val mayHaveSent: Boolean) : IOException("Scrobble communication failed")

internal class ScrobbleHttpsTransport : ScrobbleTransport {
    override suspend fun post(parameters: Map<String, String>) = withContext(Dispatchers.IO) {
        val connection = URL("https://ws.audioscrobbler.com/2.0/").openConnection() as HttpsURLConnection
        var mayHaveSent = false
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = form(parameters).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use {
                mayHaveSent = true
                it.write(body)
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readNBytes(65_537) } ?: throw IOException("Missing response")
            if (bytes.size > 65_536) throw IOException("Response too large")
            LastFmResponse(status, bytes.toString(Charsets.UTF_8))
        } catch (_: IOException) {
            throw ScrobbleNetworkException(mayHaveSent)
        } finally { connection.disconnect() }
    }
}

internal class ScrobbleClient(private val config: LastFmConfig, private val transport: ScrobbleTransport = ScrobbleHttpsTransport()) : ScrobbleApi {
    override suspend fun submit(job: ScrobbleJob, session: StoredLastFmAuth.Connected): ScrobbleResult {
        require(job.accountName == session.accountName && session.apiKey == config.apiKey)
        val params = mutableMapOf("method" to "track.scrobble", "sk" to session.sessionKey,
            "track" to job.title, "artist" to job.artist, "timestamp" to (job.startedAtMs / 1000).toString(),
            "duration" to (job.durationMs / 1000).toString())
        job.album?.let { params["album"] = it }
        return try {
            val response = transport.post(config.signed(params) + ("format" to "json"))
            val json = JSONObject(response.body)
            if (json.has("error")) return when (val code = json.getInt("error")) {
                11, 16 -> ScrobbleResult.Retry
                9 -> ScrobbleResult.Reauthenticate
                else -> ScrobbleResult.Rejected(code)
            }
            if (response.status != 200) return ScrobbleResult.Uncertain
            val scrobbles = json.getJSONObject("scrobbles")
            val counts = scrobbles.getJSONObject("@attr")
            val accepted = counts.getInt("accepted")
            val ignored = counts.getInt("ignored")
            val item = when (val value = scrobbles.get("scrobble")) {
                is JSONObject -> value
                is JSONArray -> { require(value.length() == 1); value.getJSONObject(0) }
                else -> return ScrobbleResult.Uncertain
            }
            val code = item.getJSONObject("ignoredMessage").getInt("code")
            if (item.getLong("timestamp") != job.startedAtMs / 1000) return ScrobbleResult.Uncertain
            when {
                accepted == 1 && ignored == 0 && code == 0 -> ScrobbleResult.Accepted
                accepted == 0 && ignored == 1 && code > 0 -> ScrobbleResult.Ignored(code)
                else -> ScrobbleResult.Uncertain
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: ScrobbleNetworkException) {
            if (failure.mayHaveSent) ScrobbleResult.Uncertain else ScrobbleResult.Retry
        } catch (_: Exception) { ScrobbleResult.Uncertain }
    }
}
