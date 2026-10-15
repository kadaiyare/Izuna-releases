package com.kadaiyare.izuna.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.kadaiyare.izuna.core.model.formatDuration
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.playback.PlaybackConnection
import com.kadaiyare.izuna.playback.PlaybackState
import com.kadaiyare.izuna.ui.components.MusicEmptyState
import com.kadaiyare.izuna.ui.components.MusicIcon
import com.kadaiyare.izuna.ui.components.MusicSymbol
import com.kadaiyare.izuna.ui.components.MusicToolbar

@Composable
fun PlayerScreen(state: PlaybackState, playback: PlaybackConnection, onBack: () -> Unit, onQueue: () -> Unit,
                 albumTracks: List<Track> = emptyList(), onLyrics: () -> Unit = {}) {
    val metadata = state.current?.mediaMetadata
    val registerArtwork = LocalPlayerArtworkRegistration.current
    Column(Modifier.fillMaxSize()) {
        MusicToolbar("再生中", onBack, MusicSymbol.Down) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) { MusicIcon(MusicSymbol.More, "再生メニュー") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("再生キュー") }, onClick = { menuOpen = false; onQueue() })
                    DropdownMenuItem(text = { Text("歌詞") }, onClick = { menuOpen = false; onLyrics() })
                    DropdownMenuItem(text = { Text("このアルバムをループ") },
                        enabled = state.connected && albumTracks.isNotEmpty(),
                        onClick = { menuOpen = false; playback.loopAlbum(albumTracks) })
                }
            }
        }
        if (metadata == null) {
            MusicEmptyState("曲を選んでください", "ライブラリから音楽を選ぶと、ここで再生できます。")
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("閉じる") }
            return
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth > maxHeight) {
                val landscapeArtSize = minOf(maxHeight - 24.dp, maxWidth * 0.44f, 400.dp).coerceAtLeast(120.dp)
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Box(Modifier.weight(0.45f), contentAlignment = Alignment.Center) {
                        Artwork(metadata.artworkUri?.toString(),
                            Modifier.size(landscapeArtSize).testTag("playerArtwork").onGloballyPositioned { registerArtwork?.invoke(it) })
                    }
                    Column(Modifier.weight(0.55f).testTag("playerDetailsScroll").verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        PlayerDetails(state, playback, onQueue, onLyrics)
                    }
                }
            } else {
                var detailsHeightPx by remember { mutableIntStateOf(0) }
                val detailsHeight = with(LocalDensity.current) { detailsHeightPx.toDp() }
                val reservedHeight = (if (detailsHeightPx == 0) 410.dp else detailsHeight) + 52.dp
                val artSize = minOf(maxWidth - 40.dp, (maxHeight - reservedHeight).coerceAtLeast(180.dp), 420.dp)
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Artwork(metadata.artworkUri?.toString(), Modifier.size(artSize).testTag("playerArtwork")
                        .onGloballyPositioned { registerArtwork?.invoke(it) })
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.onSizeChanged { detailsHeightPx = it.height }) {
                        PlayerDetails(state, playback, onQueue, onLyrics)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerDetails(state: PlaybackState, playback: PlaybackConnection, onQueue: () -> Unit, onLyrics: () -> Unit) {
    val current = state.current ?: return
    val metadata = current.mediaMetadata
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(metadata.title?.toString() ?: "タイトル不明", style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(metadata.artist?.toString() ?: "アーティスト不明", Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(metadata.albumTitle?.toString() ?: "アルバム不明", Modifier.padding(top = 3.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        key(current.mediaId) { AudioQualityPanel(current.mediaId) }
        Spacer(Modifier.height(20.dp))
        var seeking by remember(state.current?.mediaId) { mutableStateOf<Float?>(null) }
        val duration = state.durationMs.toFloat().coerceAtLeast(1f)
        val colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
        )
        Slider(
            value = seeking ?: state.positionMs.toFloat().coerceIn(0f, duration),
            onValueChange = { seeking = it },
            onValueChangeFinished = { seeking?.let { playback.seek(it.toLong()) }; seeking = null },
            valueRange = 0f..duration,
            enabled = state.connected && state.canSeek && state.durationMs > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
            thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White)) },
            track = { sliderState -> SliderDefaults.Track(sliderState, colors = colors, modifier = Modifier.height(4.dp)) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(seeking?.toLong() ?: state.positionMs), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatDuration(state.durationMs), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = state.shuffle, onCheckedChange = { playback.toggleShuffle() }, enabled = state.connected,
                modifier = Modifier.size(48.dp).semantics { stateDescription = if (state.shuffle) "オン" else "オフ" }) {
                MusicIcon(MusicSymbol.Shuffle, "シャッフル")
            }
            IconButton(onClick = playback::previous, enabled = state.connected && state.hasPrevious, modifier = Modifier.size(56.dp)) {
                MusicIcon(MusicSymbol.Previous, "前の曲", Modifier.size(30.dp))
            }
            FilledIconButton(
                onClick = playback::toggle, enabled = state.connected, modifier = Modifier.size(76.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
            ) {
                MusicIcon(if (state.playing || state.buffering) MusicSymbol.Pause else MusicSymbol.Play,
                    if (state.playing || state.buffering) "一時停止" else "再生", Modifier.size(34.dp))
            }
            IconButton(onClick = playback::next, enabled = state.connected && state.hasNext, modifier = Modifier.size(56.dp)) {
                MusicIcon(MusicSymbol.Next, "次の曲", Modifier.size(30.dp))
            }
            val repeatLabel = when (state.repeatMode) {
                Player.REPEAT_MODE_ALL -> "すべて"
                Player.REPEAT_MODE_ONE -> "1曲"
                else -> "オフ"
            }
            IconButton(onClick = playback::cycleRepeat, enabled = state.connected,
                modifier = Modifier.size(48.dp).semantics { stateDescription = repeatLabel }) {
                val tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides tint) {
                        MusicIcon(MusicSymbol.Repeat, "リピート: $repeatLabel")
                    }
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Text("1", fontSize = 9.sp, color = tint)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${state.currentIndex + 1} / ${state.queue.size}", Modifier.weight(1f).padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onQueue) { MusicIcon(MusicSymbol.Queue, "再生キュー") }
            TextButton(onClick = onLyrics, modifier = Modifier.testTag("openLyrics")) { Text("歌詞") }
        }
        if (state.buffering) {
            Text("読み込み中…", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
