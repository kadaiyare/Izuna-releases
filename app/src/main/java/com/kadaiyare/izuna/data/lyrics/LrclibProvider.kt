package com.kadaiyare.izuna.data.lyrics

import android.os.SystemClock
import com.kadaiyare.izuna.BuildConfig
import com.kadaiyare.izuna.core.model.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal fun interface LyricsProvider { suspend fun find(query: LyricsQuery): LyricsDocument? }
internal data class LyricsHttpResponse(val code: Int, val body: String = "", val retryAfterSeconds: Long = 0)
internal class LyricsRateLimited(val seconds: Long) : IOException()

/** Replaceable provider. Serialized requests, no publishing, no loose search fallback. */
internal class LrclibProvider(
    private val http: suspend (String) -> LyricsHttpResponse = ::fetchLyricsJson,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val intervalMs: Long = 500,
) : LyricsProvider {
    private val mutex = Mutex()
    private var nextRequest = 0L
    override suspend fun find(query: LyricsQuery): LyricsDocument? {
        if (!query.valid()) return null
        return mutex.withLock {
            val remaining = nextRequest - clock()
            if (remaining > intervalMs) throw LyricsRateLimited((remaining + 999) / 1000)
            if (remaining > 0) delay(remaining)
            nextRequest = clock() + intervalMs
            val params = linkedMapOf("track_name" to query.title, "artist_name" to query.artist,
                "duration" to query.durationSeconds.toString())
            query.album?.takeIf(String::isNotBlank)?.let { params["album_name"] = it }
            val url = "https://lrclib.net/api/get?" + params.entries.joinToString("&") {
                "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
            val response = http(url)
            currentCoroutineContext().ensureActive()
            when (response.code) {
                404 -> null
                429, 503 -> {
                    val seconds = response.retryAfterSeconds.coerceAtLeast(1)
                    val now = clock()
                    nextRequest = if (seconds > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + seconds * 1000
                    throw LyricsRateLimited(seconds)
                }
                200 -> parseResponse(response.body, query)
                else -> throw IOException("Lyrics service unavailable")
            }
        }
    }

    companion object {
        internal fun parseResponse(body: String, query: LyricsQuery): LyricsDocument? {
            val json = JSONObject(body)
            if (normalizedLyricsTag(json.optString("trackName")) != normalizedLyricsTag(query.title) ||
                normalizedLyricsTag(json.optString("artistName")) != normalizedLyricsTag(query.artist)) return null
            if (!query.album.isNullOrBlank() && normalizedLyricsTag(json.optString("albumName")) != normalizedLyricsTag(query.album)) return null
            val duration = json.optDouble("duration", Double.NaN)
            if (!duration.isFinite() || kotlin.math.abs(duration - query.durationSeconds) > 2) return null
            fun field(name: String) = if (json.isNull(name)) null else json.optString(name)
                .takeIf { it.isNotBlank() && it.length <= 131072 }
            val synced = field("syncedLyrics")?.takeIf { parseLrc(it).isNotEmpty() }
            val plain = field("plainLyrics")
            val instrumental = json.optBoolean("instrumental")
            if (!instrumental && synced == null && plain == null) return null
            return LyricsDocument("LRCLIB", json.optLong("id"), plain, synced, instrumental)
        }
        val shared: LyricsProvider by lazy { LrclibProvider() }
    }
}

internal suspend fun fetchLyricsJson(url: String): LyricsHttpResponse = withContext(Dispatchers.IO) {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host == "lrclib.net" && uri.path == "/api/get" && uri.userInfo == null && uri.port == -1)
    val connection = uri.toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5000; connection.readTimeout = 5000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Izuna/${BuildConfig.VERSION_NAME} (https://github.com/kadaiyare/Izuna-releases)")
        val code = connection.responseCode
        if (code != 200) {
            val header = connection.getHeaderField("Retry-After")
            val seconds = header?.toLongOrNull() ?: runCatching {
                (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
                    System.currentTimeMillis() + 999) / 1000
            }.getOrDefault(if (code == 503) 1 else 60)
            return@withContext LyricsHttpResponse(code, retryAfterSeconds = seconds)
        }
        if (!connection.contentType.orEmpty().substringBefore(';').equals("application/json", true)) throw IOException("Unexpected lyrics response")
        val body = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 524288) throw IOException("Lyrics response too large")
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
        LyricsHttpResponse(code, body)
    } finally { connection.disconnect() }
}
