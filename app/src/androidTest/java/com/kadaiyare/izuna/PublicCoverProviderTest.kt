package com.kadaiyare.izuna

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import com.kadaiyare.izuna.data.discord.PublicCoverProvider
import java.io.IOException
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic API responses: no tags leave the emulator during this suite. */
@RunWith(AndroidJUnit4::class)
class PublicCoverProviderTest {
    private val tags = ScrobbleMetadata("Test Song", "Test Artist", "Test Album")
    private val id = "76df3287-6cda-33eb-8e9a-044b5e15ffdd"
    private val group = "c31a5e2b-0bf8-32e0-8aeb-ef4ba9973932"
    private val url = "https://archive.org/download/test/front-500.jpg"
    private fun search(title: String = tags.title, artist: String = tags.artist, album: String = tags.album!!) =
        JSONObject().put("recordings", JSONArray().put(JSONObject().put("title", title)
            .put("artist-credit", JSONArray().put(JSONObject().put("name", artist)))
            .put("releases", JSONArray().put(JSONObject().put("id", id).put("title", album)
                .put("release-group", JSONObject().put("id", group)))))).toString()
    private fun cover(front: Boolean = true, approved: Boolean = true, image: String = url) =
        JSONObject().put("images", JSONArray().put(JSONObject().put("front", front).put("approved", approved)
            .put("thumbnails", JSONObject().put("500", image)))).toString()

    @Test fun matchesAllThreeTagsAndCachesThePublicUrl() = runBlocking {
        val calls = mutableListOf<String>()
        val provider = PublicCoverProvider({ target -> calls += target; if (target.contains("musicbrainz")) search() else cover() }, minimumIntervalMs = 0)
        assertEquals(url, provider.find(tags)); assertEquals(url, provider.find(tags))
        assertEquals(2, calls.size)
        assertTrue(calls.last().endsWith("release-group/$group"))
    }
    @Test fun titleArtistAndAlbumMustAllMatchInsteadOfUsingTopSearchResult() {
        for (body in listOf(search(title = "Wrong"), search(artist = "Wrong"), search(album = "Wrong"))) {
            assertTrue(PublicCoverProvider.candidates(body, tags).isEmpty())
        }
        assertFalse(PublicCoverProvider.candidates(search(" TEST  SONG ", "Ｔｅｓｔ Artist"), tags).isEmpty())
    }
    @Test fun missingAlbumDoesNotMakeAnyNetworkRequest() = runBlocking {
        val provider = PublicCoverProvider({ fail("No request expected"); null })
        assertNull(provider.find(tags.copy(album = null)))
        assertNull(provider.find(tags.copy(album = "<unknown>")))
    }
    @Test fun backUnapprovedAndUntrustedUrlsAreNeverUsed() {
        assertNull(PublicCoverProvider.frontImage(cover(front = false)))
        assertNull(PublicCoverProvider.frontImage(cover(approved = false)))
        for (image in listOf("https://archive.org.evil.test/a.jpg", "file:///sdcard/a.jpg", "https://user:pass@archive.org/a.jpg", "https://archive.org:8080/a.jpg"))
            assertNull(PublicCoverProvider.frontImage(cover(image = image)))
        assertEquals(url, PublicCoverProvider.frontImage(cover(image = url.replace("https:", "http:"))))
    }
    @Test fun unavailableGroupTriesTheMatchingRelease() = runBlocking {
        val calls = mutableListOf<String>()
        val provider = PublicCoverProvider({ target -> calls += target
            when { target.contains("musicbrainz") -> search(); target.contains("release-group") -> null; else -> cover() }
        }, minimumIntervalMs = 0)
        assertEquals(url, provider.find(tags))
        assertEquals(3, calls.size); assertTrue(calls.last().endsWith("release/$id"))
    }
    @Test fun failuresAreNotCachedAndSuccessfulMissesExpire() = runBlocking {
        var calls = 0; var time = 0L
        val provider = PublicCoverProvider({ calls++; if (calls == 1) throw IOException() else "{\"recordings\":[]}" }, { time }, 0)
        assertNull(provider.find(tags)); assertNull(provider.find(tags)); assertNull(provider.find(tags))
        assertEquals(6, calls)
        time += 600_001
        assertNull(provider.find(tags)); assertEquals(9, calls)
    }
    @Test fun cancellationIsPropagatedInsteadOfBecomingACachedMiss() = runBlocking {
        val provider = PublicCoverProvider({ throw CancellationException("cancelled") }, minimumIntervalMs = 0)
        try { provider.find(tags); fail("Cancellation expected") } catch (_: CancellationException) { }
    }
    @Test fun concurrentMusicBrainzRequestsAreSpaced() = runBlocking {
        val times = mutableListOf<Long>()
        val provider = PublicCoverProvider({ target -> if (target.contains("musicbrainz")) times += SystemClock.elapsedRealtime(); "{\"recordings\":[]}" }, minimumIntervalMs = 100)
        coroutineScope { repeat(3) { index -> launch { provider.find(tags.copy(album = "Album $index")) } } }
        assertEquals(9, times.size)
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 95 })
    }
    @Test fun luceneOperatorsCannotChangeTheLiteralSearch() {
        assertEquals("\"A\\\" OR artist\\:\\*\\\\B\"", PublicCoverProvider.literal("A\" OR artist:*\\B"))
    }

    private fun albumSearch(title: String = "作品集", artist: String = "Producer", japanese: Boolean = true,
        aliases: List<String> = emptyList(), artistId: String? = null): String {
        val person = JSONObject().put("name", artist).put("aliases", JSONArray(aliases.map { JSONObject().put("name", it) }))
        artistId?.let { person.put("id", it) }
        return JSONObject().put("releases", JSONArray().put(JSONObject().put("id", id).put("title", title)
            .put("country", if (japanese) "JP" else "US").put("status", "Official")
            .put("artist-credit", JSONArray().put(JSONObject().put("name", artist).put("artist", person)))
            .put("release-group", JSONObject().put("id", group)))).toString()
    }
    @Test fun albumArtistRecoversMissingRecordingsAndReusesTheCoverAcrossDifferentSingers() = runBlocking {
        val calls = mutableListOf<String>()
        val provider = PublicCoverProvider({ target -> calls += target
            when { target.contains("recording/") -> "{\"recordings\":[]}"
                target.contains("release-group/?") -> "{\"release-groups\":[]}"
                target.contains("musicbrainz") -> albumSearch()
                else -> cover() }
        }, minimumIntervalMs = 0)
        val first = ScrobbleMetadata("曲A", "Singer A", "作品集 [Disc 1]", "Producer")
        assertEquals(url, provider.find(first))
        val count = calls.size
        assertEquals(url, provider.find(first.copy(title = "曲B", artist = "Singer B")))
        assertEquals("An album match must be reusable across track artists", count, calls.size)
    }
    @Test fun aliasesAndEditionLabelsMatchButDifferentAlbumsAndArtistsAreRejected() {
        val query = ScrobbleMetadata("曲", "日本語名", "作品集 [Disc 2]")
        assertFalse(PublicCoverProvider.albumCandidates(albumSearch("作品集 (通常盤)", "Romanized", aliases = listOf("日本語名")), query).isEmpty())
        assertTrue(PublicCoverProvider.albumCandidates(albumSearch("作品集II", "日本語名"), query).isEmpty())
        assertTrue(PublicCoverProvider.albumCandidates(albumSearch("作品集", "別の人"), query).isEmpty())
        assertTrue(PublicCoverProvider.albumCandidates(albumSearch("作品集", "Various Artists"), query.copy(artist = "Various Artists")).isEmpty())
        assertEquals("eve", PublicCoverProvider.artistBase("Eve feat.初音ミク"))
        assertEquals("roselia", PublicCoverProvider.artistBase("Roselia (Member A, Member B)"))
        assertEquals("album (remix)", PublicCoverProvider.albumBase("Album (Remix)"))
    }
    @Test fun recordingNetworkFailureStillAllowsVerifiedAlbumLookupWithoutRetryingTheQuery() = runBlocking {
        val calls = mutableListOf<String>()
        val provider = PublicCoverProvider({ target -> calls += target
            when { target.contains("recording/") -> throw IOException("Cover service HTTP 503")
                target.contains("release-group/?") -> "{\"release-groups\":[]}"
                target.contains("musicbrainz") -> albumSearch()
                else -> cover() }
        }, minimumIntervalMs = 0)
        assertEquals(url, provider.find(ScrobbleMetadata("曲", "Singer", "作品集", "Producer")))
        assertEquals(1, calls.count { it.contains("recording/") })
    }
    @Test fun japaneseWaveDashesAndSubtitleSpacingMatchWithoutDroppingSubtitleIdentity() = runBlocking {
        val query = ScrobbleMetadata("曲", "Singer", "作品集 ～日本語の副題～ [Disc 2]", "Producer")
        assertEquals("作品集", PublicCoverProvider.albumSearchTerm(query.album!!))
        assertFalse(PublicCoverProvider.albumCandidates(albumSearch("作品集〜日本語の副題〜"), query).isEmpty())
        assertTrue(PublicCoverProvider.albumCandidates(albumSearch("作品集〜別の副題〜"), query).isEmpty())
        assertTrue(PublicCoverProvider.albumCandidates(albumSearch("作品集"), query).isEmpty())
        val provider = PublicCoverProvider({ target ->
            when { target.contains("recording/") -> "{\"recordings\":[]}"
                target.contains("release-group/?") -> "{\"release-groups\":[]}"
                target.contains("musicbrainz") -> albumSearch("作品集〜日本語の副題〜")
                else -> cover() }
        }, minimumIntervalMs = 0)
        assertEquals(url, provider.find(query))
    }
    @Test fun namedDiscRequiresMatchedReleaseIdMediumNameAndTrackBeforeUsingParentAlbumCover() = runBlocking {
        val query = ScrobbleMetadata("曲", "Singer", "作品集: 中層 [Middle Layer]", "Producer")
        assertEquals("作品集", PublicCoverProvider.albumSearchTerm(query.album!!))
        for ((detailId, section, title, expected) in listOf(
            listOf(id, "中層", "曲", url), listOf(group, "中層", "曲", null),
            listOf(id, "浅瀬", "曲", null), listOf(id, "中層", "別の曲", null))) {
            val provider = PublicCoverProvider({ target ->
                when { target.contains("recording/") -> "{\"recordings\":[]}"
                    target.contains("release-group/?") -> "{\"release-groups\":[]}"
                    target.contains("/ws/2/release/$id?") -> JSONObject().put("id", detailId).put("title", "作品集")
                        .put("media", JSONArray().put(JSONObject().put("title", section)
                            .put("tracks", JSONArray().put(JSONObject().put("title", title))))).toString()
                    target.contains("musicbrainz") -> albumSearch()
                    else -> cover() }
            }, minimumIntervalMs = 0)
            assertEquals(expected, provider.find(query))
        }
    }
    @Test fun aliasLookupMustBelongToTheCreditedArtist() = runBlocking {
        val personId = "bd247ab3-c22d-4b5d-a6fd-437771cacbbe"
        var wrongId = true
        val provider = PublicCoverProvider({ target ->
            when { target.contains("recording/") -> "{\"recordings\":[]}"
                target.contains("release-group/?") -> "{\"release-groups\":[]}"
                target.contains("artist/$personId") -> JSONObject().put("id", if (wrongId) group else personId)
                    .put("name", "Romanized").put("aliases", JSONArray().put(JSONObject().put("name", "日本語名"))).toString()
                target.contains("musicbrainz") -> albumSearch(artist = "Romanized", artistId = personId)
                else -> cover() }
        }, minimumIntervalMs = 0)
        val query = ScrobbleMetadata("曲", "日本語名", "作品集")
        assertNull(provider.find(query))
        // A fresh provider avoids using the deliberately cached miss.
        wrongId = false
        assertEquals(url, PublicCoverProvider({ target ->
            when { target.contains("recording/") -> "{\"recordings\":[]}"
                target.contains("release-group/?") -> "{\"release-groups\":[]}"
                target.contains("artist/$personId") -> JSONObject().put("id", personId).put("name", "Romanized")
                    .put("aliases", JSONArray().put(JSONObject().put("name", "日本語名"))).toString()
                target.contains("musicbrainz") -> albumSearch(artist = "Romanized", artistId = personId)
                else -> cover() }
        }, minimumIntervalMs = 0).find(query))
    }
    @Test fun failureOfOneImageCandidateDoesNotHideTheMatchingRelease() = runBlocking {
        val provider = PublicCoverProvider({ target ->
            when { target.contains("musicbrainz") -> search()
                target.contains("release-group") -> throw IOException("Temporary failure")
                else -> cover() }
        }, minimumIntervalMs = 0)
        assertEquals(url, provider.find(tags))
    }
}
