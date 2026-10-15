package com.kadaiyare.izuna.data.discord
import com.kadaiyare.izuna.BuildConfig

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

/** Only public image URLs cross this boundary. Local artwork never leaves the device. */
internal fun interface CoverArtProvider {
    suspend fun find(tags: ScrobbleMetadata): String?
}

/** Experimental provider: replace this implementation without changing playback or JNI. */
internal class PublicCoverProvider(
    private val json: suspend (String) -> String? = ::fetchCoverJson,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val minimumIntervalMs: Long = 1100,
) : CoverArtProvider {
    private data class Entry(val url: String?, val expires: Long)
    private val cache = LinkedHashMap<List<String>, Entry>()
    private val mutex = Mutex()
    private var nextRequest = 0L

    private val albumCache = LinkedHashMap<List<String>, Entry>()

    override suspend fun find(tags: ScrobbleMetadata): String? {
        if (!tags.valid() || tags.album.isNullOrBlank() || normalized(tags.album) in setOf("<unknown>", "不明なアルバム")) return null
        if (listOfNotNull(tags.title, tags.artist, tags.album, tags.albumArtist).any { it.length > 512 }) return null
        return mutex.withLock {
            val key = listOf(tags.title, tags.artist, tags.album.orEmpty(), tags.albumArtist.orEmpty()).map(::normalized)
            val albumKey = listOf(tags.albumArtist?.takeIf { !genericArtist(it) } ?: tags.artist, tags.album.orEmpty()).map(::normalized)
            albumCache[albumKey]?.takeIf { clock() < it.expires }?.let { return@withLock it.url }
            cache[key]?.takeIf { clock() < it.expires }?.let { return@withLock it.url }
            try {
                withTimeoutOrNull(45_000) {
                    val tried = linkedSetOf<String>()
                    var transientFailure = false
                    suspend fun metadata(path: String): String? {
                        return try { musicBrainz(path) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { transientFailure = true; null }
                    }
                    suspend fun search(resource: String, query: String): String? {
                        return metadata("$resource/?fmt=json&limit=25&query=${URLEncoder.encode(query, "UTF-8")}")
                    }
                    suspend fun image(paths: List<String>): String? {
                        for (path in paths) {
                            currentCoroutineContext().ensureActive()
                            if (tried.size >= 8) break
                            if (!tried.add(path)) continue
                            try {
                                val cover = json("https://coverartarchive.org/$path") ?: continue
                                frontImage(cover)?.let { return it }
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) { transientFailure = true }
                        }
                        return null
                    }
                    fun rememberImage(url: String?, album: Boolean = false): String? {
                        if (url != null || !transientFailure) {
                            cache[key] = Entry(url, clock() + if (url == null) 600_000 else 86_400_000)
                            trimCache(cache)
                        }
                        if (album && url != null) {
                            albumCache[albumKey] = Entry(url, clock() + 86_400_000)
                            trimCache(albumCache)
                        }
                        return url
                    }
                    // Keep exact recording evidence, including compilation track artists.
                    val recording = search("recording", "recording:${literal(tags.title)} AND artist:${literal(tags.artist)} AND release:${literal(tags.album)}")
                    recording?.let { image(candidates(it, tags)) }?.let { return@withTimeoutOrNull rememberImage(it) }

                    // An album can be catalogued even when individual recordings are missing.
                    val album = albumBase(tags.album)
                    val artist = artistBase(tags.albumArtist?.takeIf { !genericArtist(it) } ?: tags.artist)
                    val groups = search("release-group", "releasegroup:${literal(album)} AND (artist:${literal(artist)} OR artistname:${literal(artist)})")
                    groups?.let { image(albumCandidates(it, tags)) }?.let { return@withTimeoutOrNull rememberImage(it, album = true) }

                    // Searching the album alone can retrieve Japanese / romanized artist aliases.
                    val releases = JSONObject(search("release", "release:${literal(albumSearchTerm(album))}") ?: "{}")
                    image(albumCandidates(releases.toString(), tags))?.let { return@withTimeoutOrNull rememberImage(it, album = true) }
                    val rows = releases.optJSONArray("releases")
                    // A named disc is not an album suffix: prove its medium name and track on a matched release.
                    albumSection(album)?.let { (parent, section) ->
                        val paths = albumCandidates(releases.toString(), tags.copy(album = parent))
                            .filter { it.startsWith("release/") }.take(2)
                        for (path in paths) {
                            val detail = JSONObject(metadata("$path?fmt=json&inc=recordings") ?: continue)
                            if (detail.optString("id") != path.substringAfter('/') || !albumMatches(detail, parent) ||
                                !mediumMatches(detail, section, tags.title)) continue
                            val groupId = detail.optJSONObject("release-group")?.optString("id").orEmpty()
                            val coverPaths = listOf(path) + if (uuid.matches(groupId)) listOf("release-group/$groupId") else emptyList()
                            image(coverPaths)?.let { return@withTimeoutOrNull rememberImage(it, album = true) }
                        }
                        return@withTimeoutOrNull rememberImage(null)
                    }
                    val artistIds = linkedSetOf<String>()
                    if (rows != null) for (i in 0 until rows.length()) {
                        val release = rows.optJSONObject(i) ?: continue
                        if (!albumMatches(release, tags.album)) continue
                        val credits = release.optJSONArray("artist-credit") ?: continue
                        for (j in 0 until credits.length()) {
                            val id = credits.optJSONObject(j)?.optJSONObject("artist")?.optString("id").orEmpty()
                            if (uuid.matches(id)) artistIds += id
                        }
                    }
                    // Lookup is bounded, and the alias must belong to this exact credited artist.
                    for (id in artistIds.take(2)) {
                        val detail = JSONObject(metadata("artist/$id?fmt=json&inc=aliases") ?: continue)
                        if (detail.optString("id") != id) continue
                        if (rows != null) for (i in 0 until rows.length()) {
                            val credits = rows.optJSONObject(i)?.optJSONArray("artist-credit") ?: continue
                            for (j in 0 until credits.length()) {
                                val credit = credits.optJSONObject(j) ?: continue
                                if (credit.optJSONObject("artist")?.optString("id") == id) credit.put("artist", detail)
                            }
                        }
                        image(albumCandidates(releases.toString(), tags))?.let { return@withTimeoutOrNull rememberImage(it, album = true) }
                    }
                    rememberImage(null)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { null } // Transient failures are not cached or automatically retried.
        }
    }

    private suspend fun musicBrainz(path: String): String {
        val wait = nextRequest - clock()
        if (wait > 0) delay(wait)
        nextRequest = clock() + minimumIntervalMs
        return json("https://musicbrainz.org/ws/2/$path") ?: throw IOException("Missing search response")
    }

    private fun trimCache(values: LinkedHashMap<List<String>, Entry>) {
        while (values.size > 100) values.remove(values.keys.first())
    }

    companion object {
        val shared: CoverArtProvider by lazy { PublicCoverProvider() }
        private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        internal fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
            .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
            .replace(Regex("[‐‑–—−]"), "-")

        private val edition = Regex("""\s*[\[(](?:通常盤|初回(?:生産)?限定(?:盤|版)?[ab]?|完全生産限定盤[ab]?|期間生産限定盤|(?:standard|limited|deluxe) edition|(?:disc|cd)\s*\d+|\d+\s*bit\s*/\s*\d+\s*khz)[\])]$""")
        internal fun albumBase(value: String): String {
            var result = normalized(value).replace('〜', '~').replace(Regex("\\s*~\\s*"), "~")
            while (true) {
                val shorter = result.replace(edition, "").trim()
                if (shorter == result || shorter.isEmpty()) return result
                result = shorter
            }
        }
        internal fun albumSection(value: String): Pair<String, String>? {
            val parts = Regex("""^([^:]+):\s*(.+?)\s+\[[^\[\]]+\]$""").matchEntire(albumBase(value)) ?: return null
            return parts.groupValues[1].trim() to parts.groupValues[2].trim()
        }
        internal fun albumSearchTerm(value: String): String {
            val base = albumBase(value)
            albumSection(base)?.let { return it.first }
            // A broader query only; candidates still have to match the entire title and artist.
            val subtitle = Regex("""^(.+?)~[^~]+~$""").matchEntire(base)
            return subtitle?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 } ?: base
        }
        internal fun mediumMatches(detail: JSONObject, section: String, title: String): Boolean {
            val media = detail.optJSONArray("media") ?: return false
            return (0 until media.length()).any { index ->
                val medium = media.optJSONObject(index)
                val tracks = medium?.optJSONArray("tracks")
                normalized(medium?.optString("title").orEmpty()) == normalized(section) && tracks != null &&
                    (0 until tracks.length()).any { trackIndex ->
                        val track = tracks.optJSONObject(trackIndex)
                        track?.let { normalized(it.optString("title", it.optJSONObject("recording")?.optString("title").orEmpty())) } == normalized(title)
                    }
            }
        }
        internal fun artistBase(value: String) = normalized(value)
            .replace(Regex("""\s*(?:[\[(]\s*)?\b(?:(?:feat\.|ft\.)\s*|(?:feat|featuring|ft)\s+).+$"""), "").trim()
            .replace(Regex("""\s*\([^()]*[,、][^()]*\)$"""), "").trim()
        internal fun genericArtist(value: String) = normalized(value) in setOf("various artists", "various", "v.a.", "va", "ヴァリアス・アーティスト", "複数のアーティスト", "<unknown>", "アーティスト不明")

        private fun artistNames(credits: org.json.JSONArray): Set<String> {
            if (credits.length() == 0 || credits.length() > 8) return emptySet()
            val credited = buildString {
                for (i in 0 until credits.length()) credits.optJSONObject(i)?.let {
                    append(it.optString("name", it.optJSONObject("artist")?.optString("name").orEmpty()))
                    append(it.optString("joinphrase"))
                }
            }
            val canonical = buildString {
                for (i in 0 until credits.length()) credits.optJSONObject(i)?.let {
                    append(it.optJSONObject("artist")?.optString("name") ?: it.optString("name"))
                    append(it.optString("joinphrase"))
                }
            }
            val result = mutableSetOf(artistBase(credited), artistBase(canonical))
            // Do not accept just one member of an unrelated collaboration.
            val first = credits.optJSONObject(0)
            if (credits.length() == 1 || first?.optString("joinphrase")?.trim()?.matches(Regex("(?i)(feat\\.?|featuring|ft\\.?)")) == true) {
                val artist = first?.optJSONObject("artist")
                result += artistBase(first?.optString("name").orEmpty())
                result += artistBase(artist?.optString("name").orEmpty())
                val aliases = artist?.optJSONArray("aliases")
                if (aliases != null) for (i in 0 until aliases.length()) {
                    aliases.optJSONObject(i)?.optString("name")?.let { result += artistBase(it) }
                }
            }
            return result.filterTo(linkedSetOf()) { it.isNotBlank() && !genericArtist(it) }
        }
        internal fun albumMatches(entity: JSONObject, album: String): Boolean {
            if (albumBase(entity.optString("title")) == albumBase(album)) return true
            val aliases = entity.optJSONArray("aliases") ?: return false
            return (0 until aliases.length()).any { albumBase(aliases.optJSONObject(it)?.optString("name").orEmpty()) == albumBase(album) }
        }
        internal fun albumCandidates(body: String, tags: ScrobbleMetadata): List<String> {
            val root = JSONObject(body)
            val groups = root.optJSONArray("release-groups")
            val rows = groups ?: root.optJSONArray("releases") ?: return emptyList()
            val artists = listOf(tags.albumArtist?.takeIf { !genericArtist(it) } ?: tags.artist).filterNot(::genericArtist).map(::artistBase)
            val matched = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter { entity ->
                albumMatches(entity, tags.album.orEmpty()) && entity.optJSONArray("artist-credit")?.let { credits ->
                    artistNames(credits).any { it in artists }
                } == true && entity.optString("status") !in setOf("Bootleg", "Promotion", "Pseudo-Release")
            }.sortedByDescending { entity ->
                (if (normalized(entity.optString("title")) == normalized(tags.album.orEmpty())) 4 else 0) +
                    (if (entity.optString("country") == "JP") 2 else 0) +
                    (if (entity.optJSONObject("cover-art-archive")?.optBoolean("front") == true) 1 else 0)
            }
            val paths = linkedSetOf<String>()
            for (entity in matched) {
                val id = entity.optString("id")
                val group = if (groups != null) id else entity.optJSONObject("release-group")?.optString("id").orEmpty()
                // Honour the matched Japanese edition before a generic group representative.
                if (groups == null && uuid.matches(id)) paths += "release/$id"
                if (uuid.matches(group)) paths += "release-group/$group"
            }
            return paths.take(8)
        }

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
                if (artistBase(tags.artist) !in artistNames(credits)) continue
                val releases = recording.optJSONArray("releases") ?: continue
                for (j in 0 until releases.length()) {
                    val release = releases.optJSONObject(j) ?: continue
                    if (!albumMatches(release, tags.album.orEmpty())) continue
                    val group = release.optJSONObject("release-group")?.optString("id").orEmpty()
                    val id = release.optString("id")
                    if (uuid.matches(group)) result += "release-group/$group"
                    if (uuid.matches(id)) result += "release/$id"
                }
            }
            return result.take(8)
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
internal suspend fun fetchCoverJson(url: String): String? = withContext(Dispatchers.IO) {
    var uri = URI(url)
    val search = uri.host == "musicbrainz.org"
    repeat(5) {
        ensureActive()
        check(if (search) uri.scheme == "https" && uri.host == "musicbrainz.org" && uri.userInfo == null && uri.port == -1 else allowedCoverUri(uri))
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4000
            connection.readTimeout = if (search) 4000 else 8000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Izuna/${BuildConfig.VERSION_NAME} (https://github.com/kadaiyare/Izuna-releases)")
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
