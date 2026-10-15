package com.kadaiyare.izuna.data.lyrics

import android.content.Context
import android.util.AtomicFile
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import com.kadaiyare.izuna.core.model.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class LyricsLoadResult(val document: LyricsDocument?, val cached: Boolean = false, val warning: String? = null)

/** Durable, bounded local storage. Offsets are tied to the local file, covers/lyrics providers are independent. */
internal class LyricsRepository(
    private val directory: File, private val adjustments: DataStore<Preferences>,
    private val provider: LyricsProvider = LrclibProvider.shared,
) {
    private val mutex = Mutex()
    private val misses = LinkedHashMap<String, Long>()
    suspend fun load(query: LyricsQuery, online: Boolean, refresh: Boolean = false): LyricsLoadResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = key(query)
            if (!refresh) read(query)?.let { return@withLock LyricsLoadResult(it, cached = true) }
            if (!online) return@withLock LyricsLoadResult(null)
            if (!refresh && (misses[key] ?: 0) > android.os.SystemClock.elapsedRealtime()) return@withLock LyricsLoadResult(null)
            val result = provider.find(query)
            ensureActive()
            if (result == null) {
                misses[key] = android.os.SystemClock.elapsedRealtime() + 3_600_000
                while (misses.size > 100) misses.remove(misses.keys.first())
                return@withLock LyricsLoadResult(null)
            }
            val warning = try { write(query, result); null }
                catch (_: IOException) { "歌詞を端末に保存できませんでした。今回の表示は続けられます。" }
            LyricsLoadResult(result, warning = warning)
        }
    }

    suspend fun adjustment(uri: String): Long = adjustments.data.first()[longPreferencesKey(hash(uri))] ?: 0
    suspend fun adjust(uri: String, delta: Long?, reset: Boolean = false): Long {
        val key = longPreferencesKey(hash(uri))
        val saved = adjustments.edit {
            val next = if (reset) 0 else ((it[key] ?: 0) + (delta ?: 0)).coerceIn(-10_000, 10_000)
            if (next == 0L) it.remove(key) else it[key] = next
        }
        return saved[key] ?: 0
    }

    private fun read(query: LyricsQuery): LyricsDocument? = try {
        val file = directory.resolve("${key(query)}.json")
        if (!file.isFile || file.length() > 1_048_576) null else {
            val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            if (json.getInt("version") != 1 || json.getString("query") != key(query)) null else {
                fun field(name: String) = if (json.isNull(name)) null else json.optString(name).takeIf { it.length <= 131072 }
                val source = json.getString("source")
                if (source != "LRCLIB") null else LyricsDocument(source, json.getLong("id"), field("plain"), field("synced"), json.getBoolean("instrumental"))
            }
        }
    } catch (_: Exception) { null }

    private fun write(query: LyricsQuery, result: LyricsDocument) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No lyrics storage")
        val bytes = JSONObject().put("version", 1).put("query", key(query)).put("source", result.source)
            .put("id", result.recordId).put("plain", result.plain ?: JSONObject.NULL)
            .put("synced", result.synced ?: JSONObject.NULL).put("instrumental", result.instrumental)
            .toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 1_048_576) throw IOException("Lyrics too large")
        val atomic = AtomicFile(directory.resolve("${key(query)}.json"))
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: IOException) { atomic.failWrite(output); throw error }
        // Keep at most 100 records / 10 MiB, evicting the oldest downloaded records.
        val files = directory.listFiles { file -> file.extension == "json" }?.sortedBy { it.lastModified() }.orEmpty()
        var size = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (size <= 10 * 1024 * 1024 && count <= 100) break
            val length = file.length()
            if (file.delete()) { size -= length; count-- }
        }
    }

    companion object {
        @Volatile private var instance: LyricsRepository? = null
        fun get(context: Context): LyricsRepository = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext.noBackupFilesDir, CoroutineScope(SupervisorJob() + Dispatchers.IO))
                .also { instance = it }
        }
        internal fun create(root: File, scope: CoroutineScope, provider: LyricsProvider = LrclibProvider.shared): LyricsRepository {
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }, scope = scope,
                produceFile = { root.resolve("lyrics-adjustments.preferences_pb") })
            return LyricsRepository(root.resolve("lyrics"), store, provider)
        }
        internal fun key(query: LyricsQuery) = hash(listOf(query.title, query.artist, query.album.orEmpty(), query.durationSeconds.toString())
            .joinToString("\u0000"))
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
