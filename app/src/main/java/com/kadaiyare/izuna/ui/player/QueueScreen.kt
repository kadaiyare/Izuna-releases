package com.kadaiyare.izuna.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.playback.PlaybackConnection
import com.kadaiyare.izuna.playback.PlaybackState
import com.kadaiyare.izuna.ui.components.MusicToolbar
import com.kadaiyare.izuna.ui.history.HistoryState
import com.kadaiyare.izuna.ui.history.HistoryViewModel
import com.kadaiyare.izuna.ui.library.LibraryState
import kotlinx.coroutines.flow.first

@Composable
fun QueueScreen(state: PlaybackState, playback: PlaybackConnection, history: HistoryViewModel,
    library: LibraryState, granted: Boolean, onBack: () -> Unit, onHistoryPlay: (Track) -> Unit,
    onSettings: () -> Unit, active: Boolean = true) {
    val historyState by history.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        MusicToolbar("キューと履歴", onBack)
        QueueTimeline(state, historyState, library, granted, playback::selectQueueItem,
            onHistoryPlay, onSettings, history::retry, active = active)
    }
}

/** A single chronological list: finished listening sessions, current item, remaining queue. */
@Composable
internal fun QueueTimeline(state: PlaybackState, history: HistoryState, library: LibraryState,
    granted: Boolean, onQueueSelect: (Int) -> Unit, onHistoryPlay: (Track) -> Unit,
    onSettings: () -> Unit, onRetry: () -> Unit, scroll: LazyListState = rememberLazyListState(),
    active: Boolean = true) {
    val past = remember(history.entries) {
        history.entries.filter { it.endedAtMs != null }
            .sortedWith(compareBy({ it.startedAtMs }, { it.id }))
    }
    var visibleCount by rememberSaveable { mutableIntStateOf(20) }
    val shown = past.takeLast(visibleCount)
    var anchored by remember { mutableStateOf(false) }
    var currentHeightPx by remember { mutableIntStateOf(0) }
    var prepending by remember { mutableStateOf(false) }
    val current = state.current
    val upcoming = state.upcomingIndices.filter { it in state.queue.indices && it != state.currentIndex }
    val available = remember(library.tracks) { library.tracks.associateBy { it.uri } }
    val loadOlder by rememberUpdatedState {
        if (active && anchored && !prepending && shown.size < past.size &&
            scroll.layoutInfo.visibleItemsInfo.firstOrNull()?.index?.let { it <= 3 } == true) {
            prepending = true
            visibleCount = (visibleCount + 20).coerceAtMost(past.size)
        }
    }
    val historyScroll = remember(scroll) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Observe intent before consumption: a drag at the old boundary can consume zero.
                if (available.y > 0f) loadOlder()
                return Offset.Zero
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val rowHeight = with(density) { currentHeightPx.toDp() }.takeIf { it > 0.dp } ?: 90.dp
        // Keep room after the queue to centre the current song even with no upcoming items.
        val edgePadding = if (current != null) ((maxHeight - rowHeight) / 2).coerceAtLeast(8.dp) else 8.dp
        // History starts at the top when scrolled back; only an empty history needs leading room.
        val topPadding = if (shown.isEmpty()) edgePadding else 8.dp
        LaunchedEffect(active, history.loading, current != null, maxHeight, currentHeightPx) {
            if (!active) anchored = false
            else if (!anchored && !history.loading) {
                snapshotFlow { scroll.layoutInfo.viewportSize.height }.first { it > 0 }
                scroll.scrollToItem(if (current != null) shown.size else 0)
                if (current != null) {
                    val layout = scroll.layoutInfo
                    layout.visibleItemsInfo.firstOrNull { it.key == "current" }?.let { row ->
                        // Layout offsets account for padding and end clamping on every reopen.
                        val viewportCentre = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                        scroll.scrollBy(row.offset + row.size / 2f - viewportCentre)
                    }
                }
                // The first pass measures the current row; the next pass uses its exact height.
                anchored = current == null || currentHeightPx > 0
            }
        }
        LaunchedEffect(prepending, shown.size, current != null, upcoming.size) {
            if (prepending) {
                val expectedCount = shown.size + (if (current != null) 1 else 0) + upcoming.size +
                    (if (current == null && upcoming.isEmpty()) 1 else 0) + 1
                // Wait for remeasurement before another batch; don't load repeatedly in one frame.
                snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it == expectedCount }
                prepending = false
            }
        }
        LazyColumn(Modifier.fillMaxSize().nestedScroll(historyScroll).testTag("queueHistoryList"), state = scroll,
            contentPadding = PaddingValues(top = topPadding, bottom = edgePadding)) {
            items(shown, key = { "history-${it.id}" }, contentType = { "track" }) { session ->
                val track = available[session.track.uri]
                val unavailable = when {
                    !granted || library.permissionDenied -> "音楽へのアクセスが必要です"
                    library.scanning -> "曲を確認中…"
                    library.error != null -> "ライブラリを再スキャンしてください"
                    track == null -> "ファイルが見つかりません"
                    else -> null
                }
                TimelineRow(session.track.title, session.track.artist, session.track.artworkUri,
                    null, selected = false,
                    enabled = state.connected && unavailable == null, tag = "history-${session.id}",
                    first = session.id == shown.first().id,
                    last = session.id == shown.last().id && current == null && upcoming.isEmpty(),
                    unavailable = unavailable, onClick = { track?.let(onHistoryPlay) })
            }
            current?.let { media ->
                item(key = "current", contentType = "track") {
                    TimelineRow(media.mediaMetadata.title?.toString() ?: "タイトル不明",
                        media.mediaMetadata.artist?.toString() ?: "アーティスト不明",
                        media.mediaMetadata.artworkUri?.toString(),
                        if (state.playing) "● 再生中" else "● 現在の曲", selected = true,
                        enabled = state.connected, tag = "currentQueueItem", onClick = { onQueueSelect(state.currentIndex) },
                        first = shown.isEmpty(), last = upcoming.isEmpty(),
                        modifier = Modifier.onSizeChanged { currentHeightPx = it.height })
                }
            }
            items(upcoming, key = { "queue-$it" }, contentType = { "track" }) { index ->
                val media = state.queue[index]
                TimelineRow(media.mediaMetadata.title?.toString() ?: "タイトル不明",
                    media.mediaMetadata.artist?.toString() ?: "アーティスト不明",
                    media.mediaMetadata.artworkUri?.toString(), null,
                    selected = false, enabled = state.connected, tag = "queue-$index", onClick = { onQueueSelect(index) },
                    first = shown.isEmpty() && current == null && index == upcoming.first(),
                    last = index == upcoming.last())
            }
            if (current == null && upcoming.isEmpty()) item(key = "queue-empty") {
                Text("ライブラリから曲を選ぶと、ここに再生順が表示されます。", Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // A header at index zero would anchor itself when older songs are prepended, causing jumps.
            item(key = "history-status") {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text(when {
                        history.loading -> "履歴を読み込み中…"
                        shown.size < past.size -> "上にスクロールして以前の履歴を表示"
                        past.isEmpty() -> "再生履歴はまだありません"
                        else -> "最近の再生履歴"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (history.recording == false) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("履歴の記録はオフです", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onSettings) { Text("設定") }
                        }
                    }
                    history.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRetry) { Text("再読み込み") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(title: String, artist: String, artwork: String?, detail: String?,
    selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, unavailable: String? = null, first: Boolean, last: Boolean) {
    Surface(modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(
        topStart = if (first) 12.dp else 0.dp, topEnd = if (first) 12.dp else 0.dp,
        bottomStart = if (last) 12.dp else 0.dp, bottomEnd = if (last) 12.dp else 0.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            .compositeOver(MaterialTheme.colorScheme.surfaceContainer) else MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().testTag(tag).semantics { this.selected = selected }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (selected) 10.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Artwork(artwork, Modifier.size(if (selected) 48.dp else 40.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                detail?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                unavailable?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
