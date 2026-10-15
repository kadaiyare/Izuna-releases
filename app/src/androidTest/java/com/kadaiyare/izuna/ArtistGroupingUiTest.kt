package com.kadaiyare.izuna

import android.content.ContentValues
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.core.model.libraryArtist
import com.kadaiyare.izuna.data.settings.SettingsRepository
import com.kadaiyare.izuna.data.library.MediaStoreLibrary
import com.kadaiyare.izuna.ui.library.LibraryScreen
import com.kadaiyare.izuna.ui.library.LibraryState
import com.kadaiyare.izuna.ui.theme.IzunaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local fixtures and settings only; no playback or network. */
class ArtistGroupingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingCanBeChosenBothWaysAndSurvivesActivityRecreation() {
        val settings = SettingsRepository.get(compose.activity)
        val original = runBlocking { settings.data.first().groupArtistsByAlbumArtist }
        try {
            runBlocking { settings.setGroupArtistsByAlbumArtist(false) }
            openGrouping()
            compose.onNode(hasText("アルバムアーティスト") and isSelectable()).performClick()
            compose.waitUntil { runBlocking { settings.data.first().groupArtistsByAlbumArtist } }
            compose.activityRule.scenario.recreate()
            groupingRow().performScrollTo().assertTextContains("アルバムアーティスト")
            groupingRow().performClick()
            compose.onNode(hasText("楽曲のアーティスト") and isSelectable()).performClick()
            compose.waitUntil { !runBlocking { settings.data.first().groupArtistsByAlbumArtist } }
            groupingRow().assertTextContains("楽曲のアーティスト")
        } finally { runBlocking { settings.setGroupArtistsByAlbumArtist(original) } }
    }

    @Test fun compilationGroupsKeepSongCreditsAndSwitchingResetsTheOldDetail() {
        val useAlbumArtist = mutableStateOf(false)
        val query = mutableStateOf("")
        val tracks = listOf(
            Track("1", "content://media/1", "First fixture", "Guest A", "Compilation", 1000, null, 1, 1, 10, albumArtist = "Fixture Band"),
            Track("2", "content://media/2", "Second fixture", "Guest B", "Compilation", 1000, null, 2, 1, 10, albumArtist = "Fixture Band"),
            Track("3", "content://media/3", "Third fixture", "Solo", "Other", 1000, null, 1, 1),
        )
        var played = emptyList<Track>()
        compose.activity.setContent { IzunaTheme {
            LibraryScreen(LibraryState(tracks), true, true, null, {}, {}, {}, {},
                { queue, _ -> played = queue }, {}, category = "アーティスト",
                query = query.value, useAlbumArtist = useAlbumArtist.value)
        } }
        compose.onNodeWithText("3件").assertIsDisplayed()
        compose.runOnIdle { useAlbumArtist.value = true }
        compose.onNodeWithText("2件").assertIsDisplayed()
        compose.onNode(hasText("Solo") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Fixture Band") and hasClickAction()).performClick()
        compose.onNodeWithText("1アルバム · 2曲").assertIsDisplayed()
        compose.onNodeWithTag("artistAlbum-id:10").performClick()
        compose.onNodeWithText("2曲").assertIsDisplayed()
        compose.onNodeWithText("Guest A").assertIsDisplayed()
        compose.onNodeWithText("Guest B").assertIsDisplayed()
        compose.onNode(hasText("First fixture") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf("1", "2"), played.map { it.id }) }
        compose.runOnIdle { useAlbumArtist.value = false }
        compose.onNodeWithText("3件").assertIsDisplayed()
        compose.onNode(hasText("Guest B") and hasClickAction()).assertIsDisplayed()
        compose.runOnIdle { query.value = "Fixture Band" }
        compose.onNodeWithText("2件").assertIsDisplayed()
        compose.onNodeWithText("Solo").assertDoesNotExist()
    }

    private fun openGrouping() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("設定").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("プレイヤー設定").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithContentDescription("設定").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("設定").performClick()
        } else {
            // A fresh test installation can legitimately have no audio permission yet.
            compose.onNodeWithText("プレイヤー設定").performClick()
        }
        groupingRow().performScrollTo().performClick()
    }
    private fun groupingRow() = compose.onNode(hasText("アーティストの分類") and hasClickAction())

    @Test fun unindexedArtistTagsFallBackToNativeFlacWithoutReplacingIndexedNames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = compose.activity
        val file = context.cacheDir.resolve("unindexed-${System.nanoTime()}.flac")
        try {
            file.writeBytes(withAlbumArtist(instrumentation.context.assets.open("silence.flac").use { it.readBytes() }))
            val values = mapOf<String, Any?>("_id" to 1, "_display_name" to file.name, "title" to "Indexed title",
                "artist" to "<unknown>", "album" to "Indexed album", "duration" to 12000,
                "mime_type" to "audio/flac", "album_id" to 0, "track" to 1, "album_artist" to null)
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor =
                    MatrixCursor(projection!!).apply { addRow(projection.map { values[it] }.toTypedArray()) }
                override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                override fun getType(uri: Uri) = "audio/flac"
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
            }
            provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
            val track = runBlocking { MediaStoreLibrary(ContentResolver.wrap(provider)).scan() }.single()
            assertEquals("Fixture Guest", track.artist)
            assertEquals("Fixture Band", track.albumArtist)
            assertEquals("Indexed title", track.title)
            assertEquals("Indexed album", track.album)
        } finally { file.delete() }
    }

    @Test fun nativeFlacAlbumArtistIsReadFromMediaStoreWithoutReplacingArtist() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = compose.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "artist-grouping-${System.nanoTime()}.flac")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/IzunaTests")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        try {
            val source = instrumentation.context.assets.open("silence.flac").use { it.readBytes() }
            resolver.openOutputStream(uri)!!.use { it.write(withAlbumArtist(source)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            // Explicitly finish platform metadata indexing before querying the new fixture.
            val path = resolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            val indexed = CountDownLatch(1)
            MediaScannerConnection.scanFile(compose.activity, arrayOf(path), arrayOf("audio/flac")) { _, _ -> indexed.countDown() }
            assertTrue("Metadata indexing did not finish", indexed.await(15, TimeUnit.SECONDS))
            val track = runBlocking { MediaStoreLibrary(resolver).scan() }.single { it.uri == uri.toString() }
            assertEquals("Fixture Guest", track.artist)
            assertEquals("Fixture Band", track.albumArtist)
            assertEquals("Fixture Band", track.libraryArtist(true))
        } finally { resolver.delete(uri, null, null) }
    }

    /** Original tags on encoded silence; no library files are modified. */
    private fun withAlbumArtist(source: ByteArray): ByteArray {
        var position = 4
        var streamInfo: ByteArray? = null
        while (true) {
            val type = source[position].toInt() and 127
            val last = source[position].toInt() and 128 != 0
            val length = ((source[position + 1].toInt() and 255) shl 16) or
                ((source[position + 2].toInt() and 255) shl 8) or (source[position + 3].toInt() and 255)
            if (type == 0) streamInfo = source.copyOfRange(position + 4, position + 4 + length)
            position += 4 + length
            if (last) break
        }
        val comments = ByteArrayOutputStream()
        fun little(value: Int) { comments.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        val vendor = "Izuna Tests".toByteArray(Charsets.UTF_8)
        little(vendor.size); comments.write(vendor); little(4)
        listOf("TITLE=Artist grouping fixture", "ARTIST=Fixture Guest", "ALBUM=Fixture Compilation", "ALBUMARTIST=Fixture Band").forEach {
            val bytes = it.toByteArray(Charsets.UTF_8); little(bytes.size); comments.write(bytes)
        }
        val result = ByteArrayOutputStream()
        result.write("fLaC".toByteArray())
        fun block(type: Int, bytes: ByteArray) {
            result.write(type); result.write(bytes.size shr 16); result.write(bytes.size shr 8); result.write(bytes.size); result.write(bytes)
        }
        block(0, requireNotNull(streamInfo)); block(132, comments.toByteArray())
        result.write(source, position, source.size - position)
        return result.toByteArray()
    }
}
