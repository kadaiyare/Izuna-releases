package com.kadaiyare.izuna.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.ui.components.MusicHeading
import com.kadaiyare.izuna.ui.components.MusicToolbar
import com.kadaiyare.izuna.ui.library.LibraryState
import com.kadaiyare.izuna.ui.player.Artwork
import com.kadaiyare.izuna.core.model.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    library: LibraryState,
    granted: Boolean,
    connected: Boolean,
    onBack: (() -> Unit)? = null,
    onPlay: (Track) -> Unit,
    onSettings: () -> Unit,
    bottomPadding: Dp = 0.dp,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val available = remember(library.tracks) { library.tracks.associateBy { it.uri } }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val days = state.entries.groupBy { Instant.ofEpochMilli(it.startedAtMs).atZone(zone).toLocalDate() }
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) MusicToolbar("再生履歴", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomPadding + 16.dp)) {
            item { MusicHeading("再生履歴", "最近の100件 · 端末内に保存") }
            if (state.recording == false) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("履歴の記録はオフです", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onSettings) { Text("設定を開く") }
                }
            }
            state.error?.let { message ->
                item {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::retry) { Text("再読み込み") }
                    }
                }
            }
            if (state.loading) item { Text("履歴を読み込み中…", Modifier.padding(24.dp)) }
            if (!state.loading && state.entries.isEmpty() && state.error == null) {
                item {
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer) {
                        Text(if (state.recording == false) "まだ再生履歴がありません。\n設定から履歴の記録をオンにできます。"
                            else "まだ再生履歴がありません。\n曲を再生するとここに表示されます。", Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            days.forEach { (date, sessions) ->
                item(key = "day-$date") {
                    val heading = when (date) {
                        today -> "今日"
                        today.minusDays(1) -> "昨日"
                        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "M月d日" else "yyyy年M月d日", Locale.JAPANESE))
                    }
                    Text(heading, Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                itemsIndexed(sessions, key = { _, session -> "session-${session.id}" }) { index, session ->
                    val top = if (index == 0) 28.dp else 0.dp
                    val bottom = if (index == sessions.lastIndex) 28.dp else 0.dp
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                        color = MaterialTheme.colorScheme.surfaceContainer) {
                        val track = available[session.track.uri]
                        val unavailable = when {
                            !granted || library.permissionDenied -> "音楽へのアクセスが必要です"
                            library.scanning -> "曲を確認中…"
                            library.error != null -> "ライブラリを再スキャンしてください"
                            track == null -> "ファイルが見つかりません"
                            else -> null
                        }
                        Row(Modifier.fillMaxWidth().clickable(enabled = connected && unavailable == null,
                            onClick = { track?.let(onPlay) }).padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Artwork(session.track.artworkUri, Modifier.size(52.dp))
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(session.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium)
                                Text(session.track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val time = Instant.ofEpochMilli(session.startedAtMs).atZone(zone).format(DateTimeFormatter.ofPattern("H:mm"))
                                val duration = if (session.listenedMs in 1..999) "1秒未満" else formatDuration(session.listenedMs)
                                Text("$time · $duration 再生", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                unavailable?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
}
