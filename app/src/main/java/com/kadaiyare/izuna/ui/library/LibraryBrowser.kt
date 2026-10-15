package com.kadaiyare.izuna.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kadaiyare.izuna.core.model.Track
import com.kadaiyare.izuna.ui.components.*
import com.kadaiyare.izuna.ui.playlist.PlaylistScreen
import com.kadaiyare.izuna.ui.playlist.PlaylistViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryBrowser(state: LibraryState, playlists: PlaylistViewModel, granted: Boolean,
    connected: Boolean, currentId: String?, active: Boolean, bottomPadding: Dp,
    onPermission: () -> Unit, onSystemSettings: () -> Unit, onSettings: () -> Unit, onScan: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit, onLoopAlbum: (List<Track>) -> Unit, useAlbumArtist: Boolean = false) {
    val labels = listOf("曲", "アルバム", "アーティスト", "プレイリスト")
    val pager = rememberPagerState(initialPage = 1) { labels.size }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val albumGroup = rememberSaveable { mutableStateOf<String?>(null) }
    val artistGroup = rememberSaveable(useAlbumArtist) { mutableStateOf<String?>(null) }
    val artistAlbum = rememberSaveable(useAlbumArtist, artistGroup.value) { mutableStateOf<String?>(null) }
    val catalogHeader = rememberTopAppBarState()
    val albumHeader = rememberTopAppBarState()
    val artistHeader = key(useAlbumArtist) { rememberTopAppBarState() }
    val artistAlbumHeader = key(useAlbumArtist, artistGroup.value, artistAlbum.value) { rememberTopAppBarState() }
    val headerState = when {
        pager.currentPage == 1 && albumGroup.value != null -> albumHeader
        pager.currentPage == 2 && artistGroup.value != null && artistAlbum.value != null -> artistAlbumHeader
        pager.currentPage == 2 && artistGroup.value != null -> artistHeader
        else -> catalogHeader
    }
    val appBar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(headerState, canScroll = { active })
    BackHandler(enabled = active && searchOpen) { searchOpen = false; query = ""; keyboard?.hide() }
    LaunchedEffect(pager.currentPage) { keyboard?.hide() }
    if (!granted || state.permissionDenied) {
        LibraryScreen(state, granted, connected, currentId, onPermission, onSystemSettings, onSettings,
            onScan, onPlay, onLoopAlbum, active, bottomPadding)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showHeading = maxHeight > 480.dp
        val expandedHeight = if (showHeading) 96.dp else 0.dp
        val expandedPx = with(LocalDensity.current) { expandedHeight.toPx() }
        SideEffect { appBar.state.heightOffsetLimit = -expandedPx }
        val headerHeight = with(LocalDensity.current) { (expandedPx + appBar.state.heightOffset).coerceIn(0f, expandedPx).toDp() }
        Column(Modifier.fillMaxSize().nestedScroll(appBar.nestedScrollConnection)) {
            // The expanded viewing area scrolls away; the action row and tabs remain reachable.
            Box(Modifier.fillMaxWidth().height(headerHeight).clipToBounds().testTag("libraryExpandedHeader")) {
                Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(expandedHeight)
                    .graphicsLayer { alpha = 1f - appBar.state.collapsedFraction }, contentAlignment = Alignment.Center) {
                    Text("Izuna", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth().testTag("libraryToolbar").padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("ライブラリ", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onSettings) { MusicIcon(MusicSymbol.Settings, "設定") }
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) { query = ""; keyboard?.hide() } }, enabled = pager.currentPage != 3) {
                    MusicIcon(if (searchOpen) MusicSymbol.Close else MusicSymbol.Search,
                        if (searchOpen) "検索を閉じる" else "音楽を検索")
                }
                IconButton(onClick = onScan, enabled = !state.scanning) { MusicIcon(MusicSymbol.Refresh, "再スキャン") }
            }
            if (searchOpen && pager.currentPage != 3) TextField(query, { query = it }, singleLine = true,
                placeholder = { Text("曲、アーティスト、アルバム") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                shape = RoundedCornerShape(26.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            SecondaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface,
                indicator = { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pager.currentPage, matchContentSize = true),
                    color = MaterialTheme.colorScheme.onSurface) },
                divider = {}) {
                labels.forEachIndexed { index, label ->
                    Tab(selected = pager.currentPage == index, modifier = Modifier.testTag("libraryTab-$index"),
                        selectedContentColor = MaterialTheme.colorScheme.onSurface,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { scope.launch { pager.animateScrollToPage(index, animationSpec = tween(320)) } },
                        text = { Text(label, maxLines = 1, fontWeight = if (pager.currentPage == index) FontWeight.Bold else FontWeight.Medium) })
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("libraryPager"),
                userScrollEnabled = active, key = { labels[it] }) { page ->
                val pageActive = active && pager.currentPage == page
                if (page == 3) PlaylistScreen(playlists, state, granted, connected, pageActive, onPlay,
                    bottomPadding = bottomPadding, showHeading = false)
                else LibraryScreen(state, granted, connected, currentId, onPermission, onSystemSettings,
                    onSettings, onScan, onPlay, onLoopAlbum, pageActive && !searchOpen, bottomPadding,
                    category = labels[page], query = query, useAlbumArtist = useAlbumArtist,
                    selectedGroup = when (page) { 1 -> albumGroup; 2 -> artistGroup; else -> null },
                    selectedArtistAlbum = if (page == 2) artistAlbum else null,
                    onOpenGroup = {
                        val detailHeader = if (page == 1) albumHeader else artistHeader
                        detailHeader.heightOffset = 0f
                        detailHeader.contentOffset = 0f
                    })
            }
        }
    }
}
