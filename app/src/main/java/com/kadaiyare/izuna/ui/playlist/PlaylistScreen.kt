package com.kadaiyare.izuna.ui.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.ui.components.MusicEmptyState
import com.kadaiyare.izuna.ui.components.MusicHeading
import com.kadaiyare.izuna.ui.components.MusicIcon
import com.kadaiyare.izuna.ui.components.MusicSymbol
import com.kadaiyare.izuna.ui.components.MusicToolbar
import com.kadaiyare.izuna.ui.library.LibraryState
import com.kadaiyare.izuna.ui.player.Artwork
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(viewModel: PlaylistViewModel, library: LibraryState, granted: Boolean, connected: Boolean,
                   active: Boolean, onPlay: (List<Track>, Int) -> Unit, bottomPadding: Dp = 0.dp, showHeading: Boolean = true) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var deletePrompt by rememberSaveable { mutableStateOf(false) }
    var selectedUris by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val addSheet = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var addClosing by remember { mutableStateOf(false) }
    val catalogScroll = rememberLazyListState()
    val detailScroll = key(selectedId) { rememberLazyListState() }
    var catalogIndex by rememberSaveable { mutableIntStateOf(0) }
    var catalogOffset by rememberSaveable { mutableIntStateOf(0) }
    val closeDetail = {
        catalogScroll.requestScrollToItem(catalogIndex, catalogOffset)
        selectedId = null
    }
    val selected = state.lists.firstOrNull { it.id == selectedId }
    val available = remember(library.tracks) { library.tracks.associateBy { it.uri } }
    val readable = granted && !library.permissionDenied && !library.scanning && library.error == null
    val playable = if (readable) selected?.trackUris?.mapNotNull(available::get).orEmpty() else emptyList()
    BackHandler(enabled = active && selected != null && !naming && !adding && !deletePrompt, onBack = closeDetail)
    Column(Modifier.fillMaxSize()) {
        if (selected != null) MusicToolbar(selected.name, onBack = closeDetail)
        key(selectedId) {
            LazyColumn(Modifier.weight(1f).testTag("playlistList"), state = if (selected == null) catalogScroll else detailScroll,
                contentPadding = PaddingValues(bottom = bottomPadding)) {
                if (showHeading) item { MusicHeading(selected?.name ?: "プレイリスト", selected?.let { "${it.trackUris.size}曲" } ?: "好きな曲を、好きな順で。") }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                        if (selected == null) {
                            TextButton(onClick = { name = ""; naming = true }, enabled = !state.loading && state.error == null) { Text("新規作成") }
                        } else {
                            TextButton(onClick = { name = selected.name; naming = true }) { Text("名前を変更") }
                            TextButton(onClick = { deletePrompt = true }) { Text("削除") }
                        }
                    }
                    if (selected != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { selectedUris = emptyList(); adding = true }, enabled = readable) { Text("曲を追加") }
                        TextButton(onClick = { onPlay(playable, 0) }, enabled = connected && playable.isNotEmpty()) { Text("すべて再生") }
                    }
                }
                state.error?.let { message -> item { Text(message, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) } }
                if (state.loading) item { Text("読み込み中…", Modifier.padding(24.dp)) }
                if (!state.loading && selected == null && state.lists.isEmpty()) item {
                    MusicEmptyState("お気に入りをまとめましょう", "新規作成からプレイリストを作り、ライブラリの曲を追加できます。")
                }
                if (selected == null) items(state.lists, key = { it.id }) { list ->
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.testTag("playlist-${list.id}").clickable {
                            catalogIndex = catalogScroll.firstVisibleItemIndex
                            catalogOffset = catalogScroll.firstVisibleItemScrollOffset
                            selectedId = list.id
                        }.padding(24.dp)) {
                            Text(list.name, style = MaterialTheme.typography.titleMedium)
                            Text("${list.trackUris.size}曲", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    if (selected.trackUris.isEmpty()) item { MusicEmptyState("曲を追加しましょう", "「曲を追加」から、このプレイリストに入れる音楽を選べます。") }
                    items(selected.trackUris, key = { it }) { uri ->
                        val track = available[uri]
                        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clickable(enabled = readable && connected && track != null) {
                                    onPlay(playable, playable.indexOfFirst { it.uri == uri })
                                }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Artwork(track?.artworkUri, Modifier.size(48.dp))
                                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                        Text(track?.title ?: "曲が見つかりません", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                        Text(if (!readable) "ライブラリの読み込み・アクセスを確認してください" else track?.artist ?: "削除されたか、移動した可能性があります",
                                            maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = { viewModel.removeTrack(selected.id, uri) }) { MusicIcon(MusicSymbol.Close, "プレイリストから削除") }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    if (naming) AlertDialog(onDismissRequest = { naming = false }, title = { Text(if (selected == null) "プレイリストを作成" else "名前を変更") },
        text = { TextField(value = name, onValueChange = { name = it.take(100) }, label = { Text("名前") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            if (selected == null) viewModel.create(name) else viewModel.rename(selected.id, name)
            naming = false
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { naming = false }) { Text("キャンセル") } })
    if (deletePrompt && selected != null) AlertDialog(onDismissRequest = { deletePrompt = false }, title = { Text("プレイリストを削除") },
        text = { Text("「${selected.name}」を削除します。音楽ファイルは残ります。") },
        confirmButton = { TextButton(onClick = { viewModel.delete(selected.id); closeDetail(); deletePrompt = false }) { Text("削除") } },
        dismissButton = { TextButton(onClick = { deletePrompt = false }) { Text("キャンセル") } })
    if (adding && selected != null) ModalBottomSheet(onDismissRequest = { adding = false }, sheetState = addSheet) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).testTag("playlistAddSheet")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("曲を追加", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(enabled = selectedUris.isNotEmpty() && readable && !addClosing, onClick = {
                    viewModel.addTracks(selected.id, selectedUris)
                    addClosing = true
                    scope.launch {
                        try { addSheet.hide(); adding = false }
                        finally { addClosing = false }
                    }
                }) { Text("追加 (${selectedUris.size})") }
            }
            LazyColumn {
                items(library.tracks.filterNot { it.uri in selected.trackUris }, key = { it.uri }) { track ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selectedUris = if (track.uri in selectedUris) selectedUris - track.uri else selectedUris + track.uri
                    }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = track.uri in selectedUris, onCheckedChange = null)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (library.tracks.all { it.uri in selected.trackUris }) item { Text("追加できる曲がありません。", Modifier.padding(24.dp)) }
            }
        }
    }
}
