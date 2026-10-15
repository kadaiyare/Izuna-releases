package com.kadaiyare.izuna.data.listenbrainz

import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import com.kadaiyare.izuna.BuildConfig
import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The user supplies an API root, optionally ending in /1, including a reverse-proxy prefix. */
internal fun listenBrainzRoot(input: String): String {
    require(input.trim().length <= 2048)
    val uri = URI(input.trim())
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0)
    var path = uri.rawPath.orEmpty().trimEnd('/')
    require(!path.contains('%') && !path.contains('\\') && path.split('/').none { it == "." || it == ".." })
    if (path.endsWith("/1")) path = path.removeSuffix("/1")
    require(!path.endsWith("/submit-listens") && !path.endsWith("/validate-token"))
    val authority = uri.rawAuthority.lowercase().removeSuffix(":443")
    return "https://$authority$path"
}

internal fun validListenBrainzToken(token: String) = token.length in 1..512 && token.all { it.code in 33..126 }
internal fun listenBrainzQualifies(durationMs: Long, listenedMs: Long) = durationMs > 0 &&
    listenedMs >= minOf(durationMs / 2 + durationMs % 2, 240_000L)
internal data class ListenBrainzResponse(val status: Int, val body: String, val retryAfterSeconds: Long? = null) {
    override fun toString() = "ListenBrainzResponse(status=$status, body=redacted)"
}
internal class ListenBrainzNetworkFailure(val mayHaveSent: Boolean) : IOException("ListenBrainz communication failed")
internal fun interface ListenBrainzTransport {
    suspend fun request(root: String, token: String, path: String, body: String?): ListenBrainzResponse
}

internal class ListenBrainzHttpsTransport(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : ListenBrainzTransport {
    override suspend fun request(root: String, token: String, path: String, body: String?) = withContext(Dispatchers.IO) {
        require(listenBrainzRoot(root) == root && validListenBrainzToken(token))
        require(path == "/1/validate-token" || path == "/1/submit-listens")
        val connection = open(URL(root + path))
        var mayHaveSent = false
        try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Token $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Izuna/${BuildConfig.VERSION_NAME}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { mayHaveSent = true; it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readNBytes(65_537) } ?: byteArrayOf()
            if (bytes.size > 65_536) throw IOException("Response too large")
            ListenBrainzResponse(status, bytes.toString(Charsets.UTF_8),
                connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400))
        } catch (_: IOException) { throw ListenBrainzNetworkFailure(mayHaveSent) }
        finally { connection.disconnect() }
    }
}

internal sealed interface ListenBrainzResult {
    data object Accepted : ListenBrainzResult
    data class Retry(val afterSeconds: Long = 60) : ListenBrainzResult
    data object Unauthorized : ListenBrainzResult
    data object Rejected : ListenBrainzResult
    data object Uncertain : ListenBrainzResult
}

internal class ListenBrainzClient(private val transport: ListenBrainzTransport = ListenBrainzHttpsTransport()) {
    suspend fun validate(root: String, token: String): String {
        val response = transport.request(root, token, "/1/validate-token", null)
        require(response.status == 200)
        val json = JSONObject(response.body)
        require(json.opt("valid") == true)
        return json.getString("user_name").also { require(it.isNotBlank() && it.length <= 256) }
    }

    suspend fun submit(connection: ListenBrainzConnection, metadata: ScrobbleMetadata, durationMs: Long,
        startedAtMs: Long? = null): ListenBrainzResult {
        require(metadata.valid())
        return try {
            val response = transport.request(connection.root, connection.token, "/1/submit-listens",
                payload(metadata, durationMs, startedAtMs))
            when {
                response.status == 401 || response.status == 403 -> ListenBrainzResult.Unauthorized
                response.status == 429 -> ListenBrainzResult.Retry(response.retryAfterSeconds ?: 60)
                response.status in 400..499 -> ListenBrainzResult.Rejected
                response.status == 200 && JSONObject(response.body).optString("status") == "ok" -> ListenBrainzResult.Accepted
                else -> ListenBrainzResult.Uncertain
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: ListenBrainzNetworkFailure) {
            if (failure.mayHaveSent) ListenBrainzResult.Uncertain else ListenBrainzResult.Retry()
        } catch (_: Exception) { ListenBrainzResult.Uncertain }
    }

    internal fun payload(metadata: ScrobbleMetadata, durationMs: Long, startedAtMs: Long?): String {
        val extra = JSONObject().put("submission_client", "Izuna").put("submission_client_version", BuildConfig.VERSION_NAME)
        if (durationMs > 0) extra.put("duration_ms", durationMs)
        val track = JSONObject().put("track_name", metadata.title.trim()).put("artist_name", metadata.artist.trim())
            .put("additional_info", extra)
        metadata.album?.trim()?.takeIf { it.isNotEmpty() }?.let { track.put("release_name", it) }
        val listen = JSONObject().put("track_metadata", track)
        startedAtMs?.let { require(it >= 1000); listen.put("listened_at", it / 1000) }
        return JSONObject().put("listen_type", if (startedAtMs == null) "playing_now" else "single")
            .put("payload", JSONArray().put(listen)).toString()
    }
}
