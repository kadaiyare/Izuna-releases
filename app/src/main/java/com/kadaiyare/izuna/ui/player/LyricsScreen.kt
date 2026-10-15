package com.kadaiyare.izuna.ui.player

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kadaiyare.izuna.core.model.*
import com.kadaiyare.izuna.playback.PlaybackState
import com.kadaiyare.izuna.ui.components.MusicToolbar

@Composable
internal fun LyricsScreen(state: LyricsState, playback: PlaybackState, onBack: () -> Unit,
    onEnable: () -> Unit, onRetry: () -> Unit, onAdjust: (Long?, Boolean) -> Unit) {
    val lines = remember(state.document?.synced) { parseLrc(state.document?.synced) }
    var adjusting by rememberSaveable(state.uri) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("lyricsScreen")) {
        MusicToolbar("歌詞", onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(playback.current?.mediaMetadata?.title?.toString() ?: "曲を選んでください",
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playback.current?.mediaMetadata?.artist?.toString().orEmpty(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        state.message?.let {
            Text(it, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            state.document?.instrumental == true -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("この曲はインストゥルメンタルです。", style = MaterialTheme.typography.bodyMedium)
            }
            lines.isNotEmpty() -> {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("LRCLIB · 同期歌詞${if (state.cached) " · 保存済み" else ""}", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { adjusting = true }) { Text("タイミング調整") }
                }
                SyncedLyrics(lines, playback.positionMs, state.adjustmentMs, state.uri, Modifier.weight(1f))
            }
            state.document?.plain?.isNotBlank() == true -> {
                Text("LRCLIB · 同期なし${if (state.cached) " · 保存済み" else ""}", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("plainLyrics"), contentPadding = PaddingValues(24.dp)) {
                    itemsIndexed(state.document.plain.lines()) { _, line ->
                        Text(line, Modifier.fillMaxWidth().padding(vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            else -> Spacer(Modifier.weight(1f))
        }
        if (!state.online) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text("曲名・アーティスト・アルバム名・曲の長さをLRCLIBへ送り、歌詞を取得します。音楽ファイルは送信しません。設定からオフにできます。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onEnable, enabled = !state.loading, modifier = Modifier.testTag("enableOnlineLyrics")) {
                    Text("オンライン歌詞を使う")
                }
            }
        } else if (state.document == null && !state.loading) {
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("再取得") }
        }
    }
    if (adjusting) AlertDialog(onDismissRequest = { adjusting = false }, title = { Text("タイミング調整") },
        text = {
            Column {
                Text("歌詞が早ければ遅らせ、遅ければ早めます。補正はこの音楽ファイルに保存します。")
                Text("%+.1f 秒".format(state.adjustmentMs / 1000.0), Modifier.padding(vertical = 12.dp).testTag("lyricsAdjustment"),
                    style = MaterialTheme.typography.titleLarge)
                Row {
                    TextButton(onClick = { onAdjust(-500, false) }, enabled = !state.saving && state.adjustmentMs > -10_000) { Text("0.5秒早める") }
                    TextButton(onClick = { onAdjust(500, false) }, enabled = !state.saving && state.adjustmentMs < 10_000) { Text("0.5秒遅らせる") }
                }
                TextButton(onClick = { onAdjust(null, true) }, enabled = !state.saving) { Text("補正をリセット") }
            }
        }, confirmButton = { TextButton(onClick = { adjusting = false }) { Text("完了") } })
}

/** Manual browsing pauses following until requested again; pause/seek use the real Player position. */
@Composable
internal fun SyncedLyrics(lines: List<LyricLine>, positionMs: Long, adjustmentMs: Long, trackKey: String?, modifier: Modifier = Modifier) {
    val scroll = rememberLazyListState()
    var follow by rememberSaveable(trackKey) { mutableStateOf(true) }
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    val active = activeLyricIndex(lines, positionMs, adjustmentMs)
    val activeTime = lines.getOrNull(active)?.timeMs
    val anchor = if (activeTime == null) 0 else lines.indexOfFirst { it.timeMs == activeTime }
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(activeTime, follow, trackKey, lines) {
        if (follow && lines.isNotEmpty()) scroll.animateScrollToItem(anchor)
    }
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().testTag("syncedLyrics"), state = scroll,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = maxHeight * 0.3f)) {
                itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                    val selected = activeTime != null && line.timeMs == activeTime
                    Text(line.text.ifEmpty { "♪" }, Modifier.fillMaxWidth().testTag("lyricLine-$index")
                        .semantics { this.selected = selected }.padding(vertical = 14.dp),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start)
                }
            }
        }
        if (!follow) TextButton(onClick = { follow = true }, modifier = Modifier.align(Alignment.CenterHorizontally)
            .testTag("resumeLyricsFollow")) { Text("再生位置に戻る") }
    }
}
