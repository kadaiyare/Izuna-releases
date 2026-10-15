package com.kadaiyare.izuna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kadaiyare.izuna.core.model.albumQueueFor
import com.kadaiyare.izuna.playback.PlaybackConnection
import com.kadaiyare.izuna.playback.PlaybackState
import com.kadaiyare.izuna.ui.components.MusicIcon
import com.kadaiyare.izuna.ui.components.MusicSymbol
import com.kadaiyare.izuna.ui.history.HistoryViewModel
import com.kadaiyare.izuna.ui.library.LibraryBrowser
import com.kadaiyare.izuna.ui.library.LibraryViewModel
import com.kadaiyare.izuna.ui.player.Artwork
import com.kadaiyare.izuna.ui.player.PlayerScreen
import com.kadaiyare.izuna.ui.player.PlayerSheet
import com.kadaiyare.izuna.ui.player.LyricsScreen
import com.kadaiyare.izuna.ui.player.LyricsState
import com.kadaiyare.izuna.ui.player.LyricsViewModel
import com.kadaiyare.izuna.ui.player.QueueScreen
import com.kadaiyare.izuna.ui.playlist.PlaylistViewModel
import com.kadaiyare.izuna.ui.settings.LastFmScreen
import com.kadaiyare.izuna.ui.settings.LastFmViewModel
import com.kadaiyare.izuna.ui.settings.ListenBrainzViewModel
import com.kadaiyare.izuna.ui.settings.ListenBrainzScreen
import com.kadaiyare.izuna.ui.settings.SettingsScreen
import com.kadaiyare.izuna.ui.settings.DiscordDisplayScreen
import com.kadaiyare.izuna.ui.settings.SettingsViewModel
import com.kadaiyare.izuna.ui.theme.IzunaTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation

private const val QUEUE_PAGE = 0
private const val PLAYER_PAGE = 1
private const val LYRICS_PAGE = 2

@Composable
internal fun IzunaApp(library: LibraryViewModel, playback: PlaybackConnection, history: HistoryViewModel, playlists: PlaylistViewModel,
    settings: SettingsViewModel, lastFm: LastFmViewModel, listenBrainz: ListenBrainzViewModel, lyrics: LyricsViewModel, granted: Boolean,
    onPermission: () -> Unit, onSystemSettings: () -> Unit, onBrowser: (String) -> Unit) {
    val libraryState by library.state.collectAsStateWithLifecycle()
    val settingsState by settings.state.collectAsStateWithLifecycle()
    val playbackState by playback.state.collectAsStateWithLifecycle()
    val playerPager = rememberPagerState(initialPage = PLAYER_PAGE) { 3 }
    val scope = rememberCoroutineScope()
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val lyricsOpen = playerPager.currentPage == LYRICS_PAGE
    // Pager prefetch must not start lyric requests or the faster playback-position polling.
    val lyricsActive = playerOpen && lyricsOpen && playerPager.settledPage == LYRICS_PAGE
    val lyricsState by lyrics.state.collectAsStateWithLifecycle()
    val songUri = playbackState.current?.localConfiguration?.uri?.toString()
        ?: playbackState.current?.mediaId?.takeIf { it.startsWith("content://") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lyricsActive, songUri, playbackState.durationMs / 1000, lifecycle) {
        if (lyricsActive && songUri != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try { lyrics.show(songUri, playbackState.durationMs); awaitCancellation() }
                finally { lyrics.hide() }
            }
        } else lyrics.hide()
    }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    val savedScreens = rememberSaveableStateHolder()
    var miniPlayerHeightPx by remember { mutableIntStateOf(0) }
    val miniPlayerPadding = if (playbackState.current == null) 0.dp else
        with(LocalDensity.current) { miniPlayerHeightPx.toDp() }.coerceAtLeast(82.dp) + 8.dp
    val closeSettings = { settingsPage = if (settingsPage != null && settingsPage != "設定") "設定" else null }
    val openSettings = { settingsPage = "設定" }
    val openPlayer = { playerPager.requestScrollToPage(PLAYER_PAGE); playerOpen = true }
    val returnToPlayer = { scope.launch { playerPager.animateScrollToPage(PLAYER_PAGE, animationSpec = tween(320)) }; Unit }
    IzunaTheme {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = if (playerOpen || settingsPage != null) Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                } else Modifier,
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    PlaybackStatus(playbackState)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LibraryBrowser(libraryState, playlists, granted, playbackState.connected,
                            playbackState.current?.mediaId, active = !playerOpen && settingsPage == null,
                            bottomPadding = miniPlayerPadding, onPermission = onPermission, onSystemSettings = onSystemSettings,
                            onSettings = openSettings, onScan = library::scan,
                            onPlay = { tracks, index -> playback.play(tracks, index); openPlayer() },
                            onLoopAlbum = { tracks -> playback.play(tracks, 0, loop = true); openPlayer() },
                            useAlbumArtist = settingsState.settings?.groupArtistsByAlbumArtist == true)
                        if (playbackState.current != null) MiniPlayer(playbackState, playback,
                            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { miniPlayerHeightPx = it.height },
                            onOpen = openPlayer)
                    }
                }
            }
            AnimatedContent(settingsPage, modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val forward = targetState != null && (initialState == null || targetState != "設定")
                    (slideInHorizontally(tween(280)) { if (forward) it else -it } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(280)) { if (forward) -it else it } + fadeOut(tween(200)))
                }, label = "settingsNavigation") { page ->
                if (page != null) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.systemBarsPadding()) {
                            savedScreens.SaveableStateProvider(page) {
                                when (page) {
                                    "設定" -> SettingsScreen(settings, libraryState, granted, closeSettings, library::scan,
                                        onPermission, onSystemSettings, onLastFm = { settingsPage = "Last.fm" },
                                        onListenBrainz = { settingsPage = "ListenBrainz" },
                                        onDiscordDisplay = { settingsPage = "Discord表示" }, onBrowser = onBrowser)
                                    "Last.fm" -> LastFmScreen(lastFm, closeSettings, onBrowser)
                                    "ListenBrainz" -> ListenBrainzScreen(listenBrainz, closeSettings)
                                    "Discord表示" -> DiscordDisplayScreen(settings, closeSettings)
                                }
                            }
                        }
                    }
                }
            }
            BackHandler(enabled = settingsPage != null && !playerOpen, onBack = closeSettings)
            PlayerSheet(playerOpen, onDismiss = { playerOpen = false }, artworkDrag = playerPager.currentPage == PLAYER_PAGE,
                backEnabled = !lyricsOpen) {
                Column(Modifier.fillMaxSize()) {
                    PlaybackStatus(playbackState)
                    HorizontalPager(playerPager, Modifier.weight(1f).fillMaxWidth().testTag("playerPager"),
                        userScrollEnabled = playerOpen) { page ->
                        when (page) {
                            PLAYER_PAGE -> PlayerScreen(playbackState, playback, onBack = { playerOpen = false },
                                albumTracks = albumQueueFor(libraryState.tracks, playbackState.current?.mediaId),
                                onQueue = { scope.launch { playerPager.animateScrollToPage(QUEUE_PAGE, animationSpec = tween(320)) } },
                                onLyrics = { scope.launch { playerPager.animateScrollToPage(LYRICS_PAGE, animationSpec = tween(320)) } })
                            QUEUE_PAGE -> QueueScreen(playbackState, playback, history, libraryState, granted,
                                onBack = returnToPlayer,
                                onHistoryPlay = { track -> playback.play(listOf(track), 0)
                                    returnToPlayer() },
                                onSettings = { playerOpen = false; openSettings() },
                                active = playerOpen && playerPager.currentPage == QUEUE_PAGE)
                            LYRICS_PAGE -> LyricsScreen(
                                // Never show the previous song's document during a prefetched transition.
                                if (lyricsState.uri == songUri) lyricsState else
                                    LyricsState(uri = songUri, online = lyricsState.online, loading = songUri != null),
                                playbackState, onBack = returnToPlayer, onEnable = lyrics::enableOnline,
                                onRetry = lyrics::retry, onAdjust = lyrics::adjust)
                        }
                    }
                }
            }
            BackHandler(enabled = playerOpen && lyricsOpen, onBack = returnToPlayer)
        }
    }
}

@Composable
private fun PlaybackStatus(state: PlaybackState) {
    if (!state.connected && state.error == null) {
        Text("再生サービスに接続中…", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.error?.let { message ->
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.errorContainer) {
            Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MiniPlayer(state: PlaybackState, playback: PlaybackConnection, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val metadata = state.current?.mediaMetadata ?: return
    Surface(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 10.dp) {
        Column(Modifier.testTag("miniPlayer").clickable(onClick = onOpen)) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(metadata.artworkUri?.toString(), Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(metadata.title?.toString() ?: "タイトル不明", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    Text(metadata.artist?.toString() ?: "アーティスト不明", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = playback::toggle, enabled = state.connected) {
                    MusicIcon(if (state.playing || state.buffering) MusicSymbol.Pause else MusicSymbol.Play,
                        if (state.playing || state.buffering) "一時停止" else "再生")
                }
                IconButton(onClick = playback::next, enabled = state.connected && state.hasNext) { MusicIcon(MusicSymbol.Next, "次の曲") }
            }
            val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.fillMaxWidth(progress).height(2.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}
