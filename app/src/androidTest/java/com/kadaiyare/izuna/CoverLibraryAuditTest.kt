package com.kadaiyare.izuna

import android.Manifest
import android.content.ContentUris
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import com.kadaiyare.izuna.data.audio.readFlacScrobbleTags
import com.kadaiyare.izuna.data.discord.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Human opt-in only. Reads native tags, never uploads audio/artwork or sends Discord RPC. */
class CoverLibraryAuditTest {
    @Test fun auditExplicitlyAuthorizedDeviceLibrary() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verifyDeviceLibraryCovers") == "true")
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_AUDIO)
        val inventory = File(context.getExternalFilesDir(null), "cover-audit-inventory.json")
        val mode = args.getString("coverAuditMode", "inventory")
        if (mode == "inventory") {
            val tracks = JSONArray()
            var audio = 0; var flac = 0; var invalid = 0
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.MIME_TYPE),
                null, null, "${MediaStore.Audio.Media._ID} ASC")!!.use { cursor ->
                while (cursor.moveToNext()) {
                    audio++
                    if (!cursor.getString(1).endsWith(".flac", ignoreCase = true) && cursor.getString(2) != "audio/flac") continue
                    flac++
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    val tags = runCatching { context.contentResolver.openInputStream(uri)?.use(::readFlacScrobbleTags) }.getOrNull()
                    if (tags == null) { invalid++; continue }
                    tracks.put(JSONObject().put("title", tags.title).put("artist", tags.artist)
                        .put("album", tags.album ?: JSONObject.NULL).put("albumArtist", tags.albumArtist ?: JSONObject.NULL))
                }
            }
            inventory.writeText(JSONObject().put("audio", audio).put("flac", flac).put("invalid", invalid).put("tracks", tracks).toString())
            val albums = (0 until tracks.length()).map { tracks.getJSONObject(it) }.map {
                listOf(it.optString("albumArtist").takeIf { name -> name.isNotBlank() && name != "null" } ?: it.getString("artist"), it.optString("album"))
            }.distinct().size
            println("Cover inventory: audio=$audio, FLAC=$flac, valid=${tracks.length()}, invalid=$invalid, album/artist groups=$albums")
            return@runBlocking
        }
        require(mode in setOf("baseline", "improved"))
        val inventoryText = inventory.readText()
        val tracks = JSONObject(inventoryText).getJSONArray("tracks")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(inventoryText.toByteArray()).joinToString("") { "%02x".format(it) }
        val manifest = File(context.getExternalFilesDir(null), "cover-audit-fingerprint.txt")
        if (manifest.exists()) require(manifest.readText() == fingerprint) { "Inventory changed; archive the previous audit files first" }
        else manifest.writeText(fingerprint)
        val revision = args.getString("coverAuditRevision", "").orEmpty()
        require(revision.isEmpty() || revision.matches(Regex("[a-z0-9-]{1,32}")))
        val output = File(context.getExternalFilesDir(null), "cover-audit-$mode${if (revision.isEmpty()) "" else "-$revision"}.jsonl")
        val completed = if (output.exists()) output.readLines().map { JSONObject(it).getInt("index") }.toSet() else emptySet()
        val http = File(context.getExternalFilesDir(null), "cover-audit-http").apply { mkdirs() }
        val urlLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        val musicBrainzGate = Mutex()
        var nextMusicBrainz = 0L
        val outputLock = Mutex()
        var hits = if (output.exists()) output.readLines().count { JSONObject(it).optBoolean("found") } else 0
        var done = completed.size
        val start = SystemClock.elapsedRealtime()
        val workers = Semaphore(4)
        val groups = (0 until tracks.length()).groupBy { index ->
            val row = tracks.getJSONObject(index)
            listOf(row.optString("albumArtist").takeIf { it.isNotBlank() && it != "null" } ?: row.getString("artist"), row.optString("album"))
        }
        coroutineScope {
            for (indices in groups.values) launch {
                workers.withPermit {
                    var events = JSONArray()
                    var selected: ScrobbleMetadata? = null
                    val fetch: suspend (String) -> String? = { endpoint ->
                        val key = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray()).joinToString("") { "%02x".format(it) }
                        val stored = http.resolve("$key.json")
                        val uri = java.net.URI(endpoint)
                        try {
                            val body = urlLocks.computeIfAbsent(key) { Mutex() }.withLock {
                                if (stored.exists()) JSONObject(stored.readText()).let { if (it.isNull("body")) null else it.getString("body") }
                                else {
                                    val result = if (uri.host == "musicbrainz.org") musicBrainzGate.withLock {
                                        val wait = nextMusicBrainz - SystemClock.elapsedRealtime()
                                        if (wait > 0) delay(wait)
                                        nextMusicBrainz = SystemClock.elapsedRealtime() + 1100
                                        if (mode == "baseline") fetchLegacyCoverJson(endpoint) else fetchCoverJson(endpoint)
                                    } else if (mode == "baseline") fetchLegacyCoverJson(endpoint) else fetchCoverJson(endpoint)
                                    stored.writeText(JSONObject().put("body", result ?: JSONObject.NULL).toString())
                                    result
                                }
                            }
                            val event = JSONObject().put("service", uri.host).put("path", uri.path).put("missing", body == null)
                            if (body != null && uri.host == "musicbrainz.org") {
                                val root = JSONObject(body)
                                event.put("results", root.optJSONArray("recordings")?.length() ?: root.optJSONArray("release-groups")?.length() ?: root.optJSONArray("releases")?.length() ?: 0)
                                selected?.let { tags -> event.put("matched", if (mode == "baseline") LegacyCoverProvider.candidates(body, tags).size
                                    else if (uri.path.contains("recording")) PublicCoverProvider.candidates(body, tags).size else PublicCoverProvider.albumCandidates(body, tags).size) }
                            }
                            events.put(event)
                            body
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) {
                            val event = JSONObject().put("service", uri.host).put("path", uri.path).put("error", error.javaClass.simpleName)
                            Regex("Cover service HTTP (\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1)?.let { event.put("httpStatus", it.toInt()) }
                            events.put(event)
                            throw error
                        }
                    }
                    // One provider per album preserves its cache, while the shared gate limits every live MB request.
                    val provider: CoverArtProvider = if (mode == "baseline") LegacyCoverProvider(json = fetch, minimumIntervalMs = 0)
                        else PublicCoverProvider(json = fetch, minimumIntervalMs = 0)
                    for (i in indices) {
                        if (i in completed) continue
                        val row = tracks.getJSONObject(i)
                        fun optional(key: String) = if (row.isNull(key)) null else row.getString(key)
                        val tags = ScrobbleMetadata(row.getString("title"), row.getString("artist"), optional("album"), optional("albumArtist"))
                        selected = tags; events = JSONArray()
                        val begin = SystemClock.elapsedRealtime()
                        val image = withTimeoutOrNull(45_000) { provider.find(tags) }
                        outputLock.withLock {
                            if (image != null) hits++
                            output.appendText(JSONObject().put("index", i).put("found", image != null)
                                .put("url", image ?: JSONObject.NULL).put("events", events).put("elapsedMs", SystemClock.elapsedRealtime() - begin).toString() + "\n")
                            done++
                            if (done % 10 == 0 || done == tracks.length()) instrumentation.sendStatus(0, Bundle().apply {
                                putString("stream", "Cover audit $mode: $done/${tracks.length()}, found=$hits, elapsed=${(SystemClock.elapsedRealtime() - start) / 1000}s\n")
                            })
                        }
                    }
                }
            }
        }
        println("Cover audit $mode complete: ${tracks.length()} tracks, found=$hits")
    }
}
