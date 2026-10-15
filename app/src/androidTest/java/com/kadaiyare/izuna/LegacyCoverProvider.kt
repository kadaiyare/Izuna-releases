package com.kadaiyare.izuna.data.discord

import android.os.SystemClock
import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Frozen from a0f3eac, including the old transport; explicit device-library audits only. */
internal class LegacyCoverProvider(
    private val json: suspend (String) -> String? = ::fetchLegacyCoverJson,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val minimumIntervalMs: Long = 1100,
) : CoverArtProvider {
    private data class Entry(val url: String?, val expires: Long)
    private val cache = LinkedHashMap<List<String>, Entry>()
    private val mutex = Mutex()
    private var nextRequest = 0L

    override suspend fun find(tags: ScrobbleMetadata): String? {
        if (!tags.valid() || tags.album.isNullOrBlank() || normalized(tags.album) in setOf("<unknown>", "不明なアルバム")) return null
        if (listOf(tags.title, tags.artist, tags.album).any { it.length > 512 }) return null
        return mutex.withLock {
            val key = listOf(tags.title, tags.artist, tags.album).map(::normalized)
            cache[key]?.takeIf { clock() < it.expires }?.let { return@withLock it.url }
            try {
                val wait = nextRequest - clock()
                if (wait > 0) delay(wait)
                nextRequest = clock() + minimumIntervalMs
                val query = "recording:${literal(tags.title)} AND artist:${literal(tags.artist)} AND release:${literal(tags.album)}"
                val result = json("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=10&query=${URLEncoder.encode(query, "UTF-8")}")
                    ?: throw IOException("Missing search response")
                var image: String? = null
                for (candidate in candidates(result, tags)) {
                    currentCoroutineContext().ensureActive()
                    val cover = json("https://coverartarchive.org/$candidate") ?: continue
                    image = frontImage(cover)
                    if (image != null) break
                }
                currentCoroutineContext().ensureActive()
                // No persistent tags or images. Misses expire sooner than successful matches.
                cache[key] = Entry(image, clock() + if (image == null) 3_600_000 else 86_400_000)
                while (cache.size > 100) cache.remove(cache.keys.first())
                image
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { null } // Transient failures are not cached or automatically retried.
        }
    }

    companion object {
        val shared: CoverArtProvider by lazy { LegacyCoverProvider() }
        private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        internal fun literal(value: String) = "\"" + buildString {
            for (character in value.trim()) {
                if (character in "+-!():^[]\"{}~*?|&;/\\") append('\\')
                append(character)
            }
        } + "\""

        internal fun candidates(body: String, tags: ScrobbleMetadata): List<String> {
            val recordings = JSONObject(body).optJSONArray("recordings") ?: return emptyList()
            val result = linkedSetOf<String>()
            for (i in 0 until recordings.length()) {
                val recording = recordings.optJSONObject(i) ?: continue
                if (normalized(recording.optString("title")) != normalized(tags.title)) continue
                val credits = recording.optJSONArray("artist-credit") ?: continue
                val artist = buildString {
                    for (j in 0 until credits.length()) {
                        val credit = credits.optJSONObject(j) ?: continue
                        append(credit.optString("name")); append(credit.optString("joinphrase"))
                    }
                }
                if (normalized(artist) != normalized(tags.artist)) continue
                val releases = recording.optJSONArray("releases") ?: continue
                for (j in 0 until releases.length()) {
                    val release = releases.optJSONObject(j) ?: continue
                    if (normalized(release.optString("title")) != normalized(tags.album.orEmpty())) continue
                    val group = release.optJSONObject("release-group")?.optString("id").orEmpty()
                    val id = release.optString("id")
                    if (uuid.matches(group)) result += "release-group/$group"
                    if (uuid.matches(id)) result += "release/$id"
                }
            }
            return result.take(4)
        }

        internal fun frontImage(body: String): String? {
            val images = JSONObject(body).optJSONArray("images") ?: return null
            for (i in 0 until images.length()) {
                val image = images.optJSONObject(i) ?: continue
                if (!image.optBoolean("front") || !image.optBoolean("approved")) continue
                val thumbs = image.optJSONObject("thumbnails")
                for (url in listOf(thumbs?.optString("500"), thumbs?.optString("large"), thumbs?.optString("250"), image.optString("image"))) {
                    if (url.isNullOrBlank()) continue
                    val secure = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
                    val uri = runCatching { URI(secure) }.getOrNull() ?: continue
                    if (secure.length <= 256 && allowedCoverUri(uri)) return secure
                }
            }
            return null
        }
    }
}

private fun allowedCoverUri(uri: URI): Boolean {
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    return uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) && uri.fragment == null &&
        (host == "coverartarchive.org" || host == "archive.org" || host.endsWith(".archive.org"))
}

/** Bounded JSON only; follow CAA's Archive redirects, never download images in the player. */
internal suspend fun fetchLegacyCoverJson(url: String): String? = withContext(Dispatchers.IO) {
    var uri = URI(url)
    val search = uri.host == "musicbrainz.org"
    repeat(5) {
        ensureActive()
        check(if (search) uri.scheme == "https" && uri.host == "musicbrainz.org" && uri.userInfo == null && uri.port == -1 else allowedCoverUri(uri))
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4000; connection.readTimeout = 4000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Izuna/1.0 (https://github.com/kadaiyare/Izuna)")
            when (val code = connection.responseCode) {
                404 -> return@withContext null
                301, 302, 303, 307, 308 -> uri = uri.resolve(connection.getHeaderField("Location") ?: throw IOException("Missing redirect"))
                200 -> return@withContext connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 524288) throw IOException("Cover metadata too large")
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
                else -> throw IOException("Cover service HTTP $code")
            }
        } finally { connection.disconnect() }
    }
    throw IOException("Too many redirects")
}
