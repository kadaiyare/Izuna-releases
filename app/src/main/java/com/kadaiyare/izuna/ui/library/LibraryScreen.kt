package com.kadaiyare.izuna.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.core.model.albumKey
import com.kadaiyare.izuna.core.model.albumQueueFor
import com.kadaiyare.izuna.core.model.formatDuration
import com.kadaiyare.izuna.core.model.libraryArtist
import com.kadaiyare.izuna.core.model.libraryAlbums
import com.kadaiyare.izuna.core.model.libraryArtists
import com.kadaiyare.izuna.ui.components.MusicEmptyState
import com.kadaiyare.izuna.ui.components.MusicHeading
import com.kadaiyare.izuna.ui.components.MusicIcon
import com.kadaiyare.izuna.ui.components.MusicSymbol
import com.kadaiyare.izuna.ui.player.Artwork

@Composable
fun LibraryScreen(
    state: LibraryState,
    granted: Boolean,
    connected: Boolean,
    currentId: String?,
    onPermission: () -> Unit,
    onSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onScan: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onLoopAlbum: (List<Track>) -> Unit,
    active: Boolean = true,
    bottomPadding: Dp = 0.dp,
    category: String = "曲",
    query: String = "",
    useAlbumArtist: Boolean = false,
    selectedGroup: MutableState<String?>? = null,
    onOpenGroup: () -> Unit = {},
    selectedArtistAlbum: MutableState<String?>? = null,
) {
    if (!granted || state.permissionDenied) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
            item { MusicHeading("Izuna", "端末の音楽を、いつでも。") }
            item {
                Surface(Modifier.padding(20.dp), shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("音楽へのアクセス", style = MaterialTheme.typography.titleLarge)
                        Text("FLAC音源の一覧と再生には「音楽と音声」の読み取り許可が必要です。音楽ファイルを端末外へ送信することはありません。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onPermission, modifier = Modifier.fillMaxWidth()) { Text("アクセスを許可") }
                        TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("アプリ設定を開く") }
                        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("プレイヤー設定") }
                    }
                }
            }
        }
        return
    }
    val groupByAlbumArtist = category == "アーティスト" && useAlbumArtist
    val selection = selectedGroup ?: rememberSaveable(category, groupByAlbumArtist) { mutableStateOf<String?>(null) }
    var group by selection
    val albumSelection = selectedArtistAlbum ?: rememberSaveable(category, groupByAlbumArtist, group) {
        mutableStateOf<String?>(null)
    }
    var artistAlbumKey by albumSelection
    val showingArtistAlbum = category == "アーティスト" && group != null && artistAlbumKey != null
    // The catalog stays alive while the detail gets a fresh position on every opening.
    val catalogScroll = key(category, groupByAlbumArtist) { rememberLazyListState() }
    val detailScroll = key(category, groupByAlbumArtist, group) { rememberLazyListState() }
    val artistAlbumScroll = key(category, groupByAlbumArtist, group, artistAlbumKey) { rememberLazyListState() }
    var catalogIndex by rememberSaveable(category, groupByAlbumArtist) { mutableIntStateOf(0) }
    var catalogOffset by rememberSaveable(category, groupByAlbumArtist) { mutableIntStateOf(0) }
    var artistAlbumIndex by rememberSaveable(category, groupByAlbumArtist, group) { mutableIntStateOf(0) }
    var artistAlbumOffset by rememberSaveable(category, groupByAlbumArtist, group) { mutableIntStateOf(0) }
    val closeGroup = {
        if (category == "アーティスト" && group != null && artistAlbumKey != null) {
            detailScroll.requestScrollToItem(artistAlbumIndex, artistAlbumOffset)
            artistAlbumKey = null
        } else {
            // Pin the next catalog measure before switching content.
            catalogScroll.requestScrollToItem(catalogIndex, catalogOffset)
            group = null
        }
    }
    BackHandler(enabled = active && group != null, onBack = closeGroup)
    val filtered = remember(state.tracks, query, category, group, groupByAlbumArtist) {
        state.tracks.filter {
            (group == null || (if (category == "アルバム") it.albumKey() else it.libraryArtist(groupByAlbumArtist)) == group) &&
                (query.isBlank() || listOfNotNull(it.title, it.artist, it.album, it.albumArtist).any { tag -> tag.contains(query.trim(), ignoreCase = true) })
        }
    }
    val groups = remember(filtered, category, groupByAlbumArtist) {
        when (category) {
            "アルバム" -> libraryAlbums(filtered)
            "アーティスト" -> libraryArtists(filtered, groupByAlbumArtist)
            else -> emptyList()
        }
    }
    val artistAlbums = remember(filtered, category, group) {
        if (category == "アーティスト" && group != null) libraryAlbums(filtered) else emptyList()
    }
    val displayedTracks = remember(filtered, groups, category, group, artistAlbums, showingArtistAlbum, artistAlbumKey) {
        when {
            showingArtistAlbum -> artistAlbums.firstOrNull { it.key == artistAlbumKey }?.tracks.orEmpty()
            category == "アルバム" && group != null -> groups.firstOrNull { it.key == group }?.tracks.orEmpty()
            artistAlbums.isNotEmpty() -> artistAlbums.flatMap { it.tracks }
            else -> filtered
        }
    }
    val groupName = when {
        showingArtistAlbum -> state.tracks.firstOrNull {
            it.libraryArtist(groupByAlbumArtist) == group && it.albumKey() == artistAlbumKey
        }?.album
        category == "アーティスト" -> group
        else -> state.tracks.firstOrNull { it.albumKey() == group }?.album
    }
    val loopTracks = when {
        showingArtistAlbum && !groupName.isNullOrBlank() && groupName != "アルバム不明" ->
            libraryAlbums(state.tracks.filter { it.libraryArtist(groupByAlbumArtist) == group })
            .firstOrNull { it.key == artistAlbumKey }?.tracks.orEmpty()
        category == "アルバム" && group != null -> albumQueueFor(state.tracks, filtered.firstOrNull()?.id)
        else -> emptyList()
    }
    // A separate layout identity prevents detail measurement from adjusting the hidden catalog.
    key(category, groupByAlbumArtist, group, artistAlbumKey) {
        LazyColumn(Modifier.fillMaxSize().testTag("libraryList-$category"),
            state = when { group == null -> catalogScroll; showingArtistAlbum -> artistAlbumScroll; else -> detailScroll },
            contentPadding = PaddingValues(bottom = bottomPadding)) {
            if (group != null) item {
                Text(groupName ?: "ライブラリ", Modifier.testTag("libraryGroupTitle-$category").padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item(key = "summary") {
                val summary = when {
                    category == "アーティスト" && group != null && !showingArtistAlbum -> "${artistAlbums.size}アルバム · ${filtered.size}曲"
                    category == "曲" || group != null -> "${displayedTracks.size}曲"
                    else -> "${groups.size}件"
                }
                val actions: @Composable () -> Unit = {
                    if (displayedTracks.isNotEmpty()) {
                        if (loopTracks.isNotEmpty()) {
                            TextButton(onClick = { onLoopAlbum(loopTracks) }, enabled = connected) {
                                MusicIcon(MusicSymbol.Repeat, null, Modifier.size(16.dp))
                                Text("ループ", Modifier.padding(start = 6.dp))
                            }
                        }
                        TextButton(onClick = { onPlay(displayedTracks, 0) }, enabled = connected) {
                            MusicIcon(MusicSymbol.Play, null, Modifier.size(16.dp))
                            Text("すべて再生", Modifier.padding(start = 6.dp))
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    if (group != null) {
                        // Counts never compete with all three action buttons for a weighted remainder.
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = closeGroup) {
                                MusicIcon(MusicSymbol.Back, null, Modifier.size(16.dp))
                                Text(if (showingArtistAlbum) "アルバム一覧" else "一覧")
                            }
                            Text(summary, Modifier.weight(1f).align(Alignment.CenterVertically).padding(start = 8.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            actions()
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            actions()
                        }
                    }
                }
            }
            if (state.scanning) {
                item {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("音楽を読み込み中…", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            state.error?.let { message ->
                item { Text(message, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
            }
            if (displayedTracks.isEmpty() && !state.scanning) {
                item {
                    MusicEmptyState(
                        if (state.tracks.isEmpty()) "音楽を追加しましょう" else "曲が見つかりません",
                        if (state.tracks.isEmpty()) "端末にFLACを保存して、右上の更新ボタンを押してください。" else "別の曲名やアーティストで検索してください。",
                    )
                }
            }
            if (category == "アーティスト" && group != null && !showingArtistAlbum) {
                itemsIndexed(artistAlbums, key = { _, album -> album.key }) { index, album ->
                    LibraryGroupButton(album.name, album.tracks, "artistAlbum-${album.key}", index, artistAlbums.size) {
                        artistAlbumIndex = detailScroll.firstVisibleItemIndex
                        artistAlbumOffset = detailScroll.firstVisibleItemScrollOffset
                        artistAlbumKey = album.key
                    }
                }
            } else if (category == "曲" || group != null) {
                itemsIndexed(displayedTracks, key = { _, track -> track.id }) { index, track ->
                    Surface(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = listRowShape(index, displayedTracks.size)) {
                        TrackRow(track, selected = track.id == currentId, enabled = connected) { onPlay(displayedTracks, index) }
                    }
                }
            } else {
                itemsIndexed(groups, key = { _, item -> item.key }) { index, (groupKey, name, tracks) ->
                    LibraryGroupButton(name, tracks, "libraryGroup-$groupKey", index, groups.size) {
                        catalogIndex = catalogScroll.firstVisibleItemIndex
                        catalogOffset = catalogScroll.firstVisibleItemScrollOffset
                        onOpenGroup()
                        group = groupKey
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun LibraryGroupButton(name: String, tracks: List<Track>, tag: String, index: Int, count: Int, onClick: () -> Unit) {
    Surface(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        shape = listRowShape(index, count)) {
        Row(Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(tracks.first().artworkUri, Modifier.size(48.dp))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${tracks.size}曲", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
            }
            MusicIcon(MusicSymbol.Back, null, Modifier.size(16.dp).graphicsLayer(rotationZ = 180f))
        }
    }
}

private fun listRowShape(index: Int, count: Int) = RoundedCornerShape(
    topStart = if (index == 0) 24.dp else 0.dp,
    topEnd = if (index == 0) 24.dp else 0.dp,
    bottomStart = if (index == count - 1) 24.dp else 0.dp,
    bottomEnd = if (index == count - 1) 24.dp else 0.dp,
)

@Composable
fun TrackRow(track: Track, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artworkUri, Modifier.size(44.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp))
        }
        Text(formatDuration(track.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
