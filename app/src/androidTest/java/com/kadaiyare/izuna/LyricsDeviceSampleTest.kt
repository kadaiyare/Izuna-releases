package com.kadaiyare.izuna

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.LyricsQuery
import com.kadaiyare.izuna.core.model.normalizedLyricsTag
import com.kadaiyare.izuna.core.model.parseLrc
import com.kadaiyare.izuna.data.audio.readFlacScrobbleTags
import com.kadaiyare.izuna.data.lyrics.LrclibProvider
import com.kadaiyare.izuna.data.lyrics.fetchLyricsJson
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, five album samples; records metadata/status only, never lyric text or RPC. */
class LyricsDeviceSampleTest {
    @Test fun inspectFiveNativeFlacAlbumSamplesThroughTheRealProvider() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyDeviceLyrics") == "true")
        val context = instrumentation.targetContext
        val queries = mutableListOf<LyricsQuery>()
        val albums = mutableSetOf<List<String>>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION),
            null, null, "${MediaStore.Audio.Media._ID} ASC")!!.use { cursor ->
            while (queries.size < 5 && cursor.moveToNext()) {
                if (!cursor.getString(1).endsWith(".flac", ignoreCase = true)) continue
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                val tags = context.contentResolver.openInputStream(uri)?.use(::readFlacScrobbleTags) ?: continue
                if (tags.artist == "Izuna Tests") continue
                val query = LyricsQuery(tags.title, tags.artist, tags.album, (cursor.getLong(2) / 1000).toInt())
                if (query.valid() && albums.add(listOf(tags.albumArtist ?: tags.artist, tags.album.orEmpty()))) queries += query
            }
        }
        assertEquals("Need five distinct album samples", 5, queries.size)
        val rows = JSONArray()
        var selected: JSONObject? = null
        var selectedQuery: LyricsQuery? = null
        val provider = LrclibProvider(http = { endpoint ->
            fetchLyricsJson(endpoint).also { response ->
                selected!!.put("httpStatus", response.code)
                if (response.code == 200) {
                    val body = JSONObject(response.body)
                    val query = selectedQuery!!
                    selected!!.put("titleMatched", normalizedLyricsTag(body.optString("trackName")) == normalizedLyricsTag(query.title))
                        .put("artistMatched", normalizedLyricsTag(body.optString("artistName")) == normalizedLyricsTag(query.artist))
                        .put("albumMatched", normalizedLyricsTag(body.optString("albumName")) == normalizedLyricsTag(query.album.orEmpty()))
                        .put("durationDeltaSeconds", body.getDouble("duration") - query.durationSeconds)
                }
            }
        })
        for (query in queries) {
            val row = JSONObject().put("title", query.title).put("artist", query.artist)
                .put("album", query.album ?: JSONObject.NULL).put("durationSeconds", query.durationSeconds)
            selected = row; selectedQuery = query
            try {
                val document = provider.find(query)
                row.put("matched", document != null).put("syncedLines", parseLrc(document?.synced).size)
                    .put("plain", !document?.plain.isNullOrBlank()).put("instrumental", document?.instrumental == true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { row.put("error", error.javaClass.simpleName) }
            rows.put(row)
        }
        File(context.getExternalFilesDir(null), "lyrics-device-sample.json").writeText(rows.toString())
        println("Device lyrics sample: ${rows.length()} queries, matched=${(0 until rows.length()).count { rows.getJSONObject(it).optBoolean("matched") }}")
    }
}
