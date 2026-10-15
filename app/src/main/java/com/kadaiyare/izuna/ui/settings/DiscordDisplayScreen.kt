package com.kadaiyare.izuna.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kadaiyare.izuna.data.discord.*
import com.kadaiyare.izuna.ui.components.MusicToolbar

@Composable
fun DiscordDisplayScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiscordDisplayContent(state, onBack, viewModel::discordShowAlbum, viewModel::discordTitleFormat,
        viewModel::discordCompactDisplay, viewModel::retry)
}

@Composable
internal fun DiscordDisplayContent(state: SettingsState, onBack: () -> Unit,
    onAlbum: (Boolean) -> Unit, onTitle: (DiscordTitleFormat) -> Unit,
    onCompact: (DiscordCompactDisplay) -> Unit, onRetry: () -> Unit = {}) {
    val settings = state.settings?.discordDisplay ?: DiscordDisplaySettings()
    val enabled = state.settings != null && !state.saving
    val preview = settings.format("サンプルの曲", "サンプルのアーティスト", "サンプルのアルバム")
    Column(Modifier.fillMaxSize()) {
        MusicToolbar("Discordの表示設定", onBack)
        LazyColumn(Modifier.fillMaxSize().testTag("discordDisplayList"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("共有がオンの再生中だけ反映されます。ここで設定しても共有はオンになりません。",
                Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.settings == null && state.error == null) item { Text("設定を読み込み中…", Modifier.padding(24.dp)) }
            state.error?.let { message -> item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry, enabled = !state.saving) { Text("再読み込み") }
                }
            } }
            item {
                SettingsLabel("表示プレビュー")
                SettingsGroup {
                    Column(Modifier.padding(20.dp)) {
                        Text("Izuna", style = MaterialTheme.typography.titleSmall)
                        Text(preview.title, Modifier.padding(top = 8.dp).testTag("discordPreviewTitle"), style = MaterialTheme.typography.titleMedium)
                        Text(preview.subtitle, Modifier.padding(top = 4.dp).testTag("discordPreviewSubtitle"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(Modifier.padding(vertical = 16.dp))
                        Text("1行表示", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(preview.compact, Modifier.padding(top = 4.dp).testTag("discordPreviewCompact"))
                    }
                }
                Text("サンプルの表示です。Discordの画面・バージョンにより見え方は異なります。",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SettingsLabel("タイトル行")
                SettingsGroup { Column(Modifier.selectableGroup()) {
                    DisplayChoice("曲名", settings.titleFormat == DiscordTitleFormat.SONG, enabled) { onTitle(DiscordTitleFormat.SONG) }
                    DisplayChoice("曲名 · アーティスト", settings.titleFormat == DiscordTitleFormat.SONG_ARTIST, enabled) { onTitle(DiscordTitleFormat.SONG_ARTIST) }
                    DisplayChoice("アーティスト · 曲名", settings.titleFormat == DiscordTitleFormat.ARTIST_SONG, enabled) { onTitle(DiscordTitleFormat.ARTIST_SONG) }
                } }
            }
            item {
                SettingsLabel("アルバム")
                SettingsGroup { SettingsToggle("アルバム名を表示", "アーティストの横に表示します。アルバム名のタグがない曲は省略します。",
                    settings.showAlbum, enabled, onAlbum) }
            }
            item {
                SettingsLabel("1行表示")
                SettingsGroup { Column(Modifier.selectableGroup()) {
                    DisplayChoice("アプリ名（Izuna）", settings.compactDisplay == DiscordCompactDisplay.APP_NAME, enabled) { onCompact(DiscordCompactDisplay.APP_NAME) }
                    DisplayChoice("曲のタイトル行", settings.compactDisplay == DiscordCompactDisplay.TITLE, enabled) { onCompact(DiscordCompactDisplay.TITLE) }
                } }
                Text("メンバー一覧などの短い表示を選びます。曲を選ぶと上のタイトル形式を使います。",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DisplayChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
