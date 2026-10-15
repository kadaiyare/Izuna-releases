package com.kadaiyare.izuna.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kadaiyare.izuna.BuildConfig
import com.kadaiyare.izuna.R
import com.kadaiyare.izuna.ui.components.MusicHeading
import com.kadaiyare.izuna.ui.components.MusicToolbar
import com.kadaiyare.izuna.ui.library.LibraryState
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import com.kadaiyare.izuna.widget.NowPlayingWidgetProvider

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    library: LibraryState,
    granted: Boolean,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onPermission: () -> Unit,
    onSystemSettings: () -> Unit,
    onLastFm: () -> Unit,
    onListenBrainz: () -> Unit = {},
    onDiscordDisplay: () -> Unit = {},
    onBrowser: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val enabled = state.settings != null && !state.saving
    val context = LocalContext.current
    val widgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    val canPinWidget = remember(widgetManager) { widgetManager.isRequestPinAppWidgetSupported }
    var widgetMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var artistGroupingOpen by rememberSaveable { mutableStateOf(false) }
    var clearHistoryOpen by rememberSaveable { mutableStateOf(false) }
    var informationDocument by rememberSaveable { mutableStateOf<String?>(null) }
    informationDocument?.let { document ->
        val content = remember(document, context) { context.assets.open(document).bufferedReader().use { it.readText() } }
        AlertDialog(
            onDismissRequest = { informationDocument = null },
            title = { Text(if (document == "privacy.txt") "プライバシー" else "ライセンス") },
            text = { LazyColumn { item { Text(content, style = MaterialTheme.typography.bodySmall) } } },
            confirmButton = { TextButton(onClick = { informationDocument = null }) { Text("閉じる") } },
        )
    }
    if (clearHistoryOpen) AlertDialog(
        onDismissRequest = { clearHistoryOpen = false },
        title = { Text("再生履歴を消去しますか？") },
        text = { Text("端末内の再生履歴をすべて消去します。元に戻せません。音楽ファイル・プレイリスト・再生キューと、外部連携の送信待ちデータは残ります。履歴記録がオンの場合は、消去後の再生を新しく記録します。") },
        confirmButton = { TextButton(onClick = { clearHistoryOpen = false; viewModel.clearHistory() }, enabled = enabled) { Text("消去する") } },
        dismissButton = { TextButton(onClick = { clearHistoryOpen = false }) { Text("キャンセル") } },
    )
    if (artistGroupingOpen) AlertDialog(
        onDismissRequest = { artistGroupingOpen = false },
        title = { Text("アーティストの分類") },
        text = { Column(Modifier.selectableGroup()) {
            Text("アルバムアーティストがない曲は、楽曲のアーティストで分類します。",
                style = MaterialTheme.typography.bodySmall)
            listOf(false to "楽曲のアーティスト", true to "アルバムアーティスト").forEach { (albumArtist, label) ->
                val selected = (state.settings?.groupArtistsByAlbumArtist == true) == albumArtist
                Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = {
                    viewModel.groupArtistsByAlbumArtist(albumArtist)
                    artistGroupingOpen = false
                }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected, onClick = null, enabled = enabled)
                    Text(label, Modifier.padding(start = 12.dp))
                }
            }
        } },
        confirmButton = { TextButton(onClick = { artistGroupingOpen = false }) { Text("閉じる") } },
    )
    Column(Modifier.fillMaxSize()) {
        MusicToolbar("設定", onBack)
        LazyColumn(Modifier.fillMaxSize().testTag("settingsList"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { MusicHeading("設定", "自分に合った使い方で。") }
            if (state.settings == null && state.error == null) item {
                Text("設定を読み込み中…", Modifier.padding(horizontal = 24.dp))
            }
            state.error?.let { message -> item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::retry, enabled = !state.saving) { Text("再読み込み") }
                }
            } }
            item {
                SettingsLabel("再生")
                SettingsGroup {
                    SettingsToggle("起動時に前のキューを復元", "次回起動時に曲・位置・再生モードを戻します。再生は一時停止状態で始まります。",
                        state.settings?.restoreQueue ?: false, enabled, viewModel::restoreQueue)
                }
            }
            item {
                SettingsLabel("履歴")
                SettingsGroup {
                    SettingsToggle("再生履歴を記録", "曲と再生時間を端末内に保存します。オフでも保存済みの履歴は残ります。",
                        state.settings?.recordHistory ?: false, enabled, viewModel::recordHistory)
                    SettingsAction("再生履歴を消去", "端末内に保存した再生履歴をすべて消去します。", enabled) { clearHistoryOpen = true }
                }
                state.historyMessage?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                SettingsLabel("ライブラリ")
                SettingsGroup {
                    SettingsAction("アーティストの分類", if (state.settings?.groupArtistsByAlbumArtist == true)
                        "アルバムアーティスト" else "楽曲のアーティスト", enabled) { artistGroupingOpen = true }
                    val hasPermission = granted && !library.permissionDenied
                    SettingsAction("再スキャン", when {
                        !hasPermission -> "音楽へのアクセスを許可してください"
                        library.scanning -> "曲を確認中…"
                        library.error != null -> library.error
                        else -> "端末内のFLACを探します · ${library.tracks.size}曲"
                    }, hasPermission && !library.scanning, onScan)
                    SettingsAction("音楽へのアクセス", if (hasPermission) "許可済み · Androidのアプリ設定を開く" else "未許可 · タップして許可する",
                        true, if (hasPermission) onSystemSettings else onPermission)
                }
            }
            item {
                SettingsLabel("歌詞")
                SettingsGroup {
                    SettingsToggle("オンライン歌詞を取得", "歌詞画面を開くと、曲名・アーティスト・アルバム名・曲の長さをLRCLIBへ送り、歌詞を探します。取得した歌詞は端末に保存します。音楽ファイルは送信しません。",
                        state.settings?.onlineLyrics == true, enabled, viewModel::onlineLyrics)
                }
            }
            item {
                SettingsLabel("ホーム画面")
                SettingsGroup {
                    SettingsAction("再生ウィジェットを追加", if (canPinWidget) "曲名と再生操作をホーム画面に置きます。" else "ホーム画面を長押しし、ウィジェット一覧からIzunaを選んでください。",
                        canPinWidget) {
                        val requested = try {
                            widgetManager.requestPinAppWidget(ComponentName(context, NowPlayingWidgetProvider::class.java), null, null)
                        } catch (_: Exception) { false }
                        widgetMessage = if (requested) "ホーム画面の確認画面で追加してください。" else "追加できませんでした。ホーム画面のウィジェット一覧からIzunaを選んでください。"
                    }
                }
                widgetMessage?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                SettingsLabel("外部連携")
                SettingsGroup {
                    SettingsAction("Last.fm", "アカウントの接続・解除", true, onLastFm)
                    SettingsAction("ListenBrainz互換サーバー", "API URL・トークン・Scrobbleの設定", true, onListenBrainz)
                    SettingsToggle("Discordで曲情報を共有", "${state.discord.message}\nオンにすると曲名・アーティスト・再生位置と、表示設定で選んだアルバム名を公開プロフィールへ送ります。停止・オフ時に消去を要求します。",
                        state.discord.available && state.settings?.discordSharing == true, enabled && state.discord.available, viewModel::discordSharing)
                    SettingsAction("Discordの表示設定", "タイトルの形式・アルバム名・1行表示", true, onDiscordDisplay)
                    SettingsToggle("Discordで公開カバーを表示（試験）", "曲情報の共有中、曲名・アーティスト・アルバム名・アルバムアーティストをMusicBrainzへ送り、Cover Art Archiveの画像を探します。一致しない曲は画像なしで共有します。端末内の画像は送信しません。",
                        state.settings?.discordCoverLookup == true, enabled, viewModel::discordCoverLookup)
                }
            }
            item {
                SettingsLabel("このアプリについて")
                SettingsGroup {
                    SettingsAction("プライバシー", "保存する情報・外部通信・削除方法", true) { informationDocument = "privacy.txt" }
                    SettingsAction("ライセンス", "Discord SDKの第三者ライセンス", true) { informationDocument = "discord-sdk-notices.txt" }
                }
            }
            item { AppInformationFooter(onBrowser) }
        }
    }
}

@Composable
private fun AppInformationFooter(onBrowser: (String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val linkStyles = TextLinkStyles(SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    ))
    val repositoryUrl = "https://github.com/kadaiyare/Izuna-releases"
    val authorUrl = "https://github.com/kadaiyare"
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)) {
        Row {
            Text(stringResource(R.string.app_name), Modifier.alignByBaseline(),
                style = MaterialTheme.typography.headlineSmall)
            Text("by kadaiyare", Modifier.alignByBaseline().padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = muted)
        }
        Text(buildAnnotatedString {
            withLink(LinkAnnotation.Url(repositoryUrl, linkStyles) { onBrowser(repositoryUrl) }) {
                append(repositoryUrl)
            }
            withStyle(SpanStyle(color = muted, fontSize = 11.sp)) {
                append(" (スターして貰えると喜びます)")
            }
        }, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        Text(buildAnnotatedString {
            withLink(LinkAnnotation.Url(authorUrl, linkStyles) { onBrowser(authorUrl) }) {
                append(authorUrl)
            }
        }, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        Text("バージョン ${BuildConfig.VERSION_NAME}", Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = muted)
        Text("端末のFLACをオフラインで再生します。\n音源品質の表示はファイルの情報です。",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = muted)
        Text("© 2026 kadaiyare", Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

@Composable
internal fun SettingsLabel(text: String) {
    Text(text, Modifier.padding(start = 24.dp, top = 22.dp, bottom = 10.dp), style = MaterialTheme.typography.titleSmall)
}

@Composable
internal fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) { Column { content() } }
}

@Composable
internal fun SettingsToggle(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SettingsAction(title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
