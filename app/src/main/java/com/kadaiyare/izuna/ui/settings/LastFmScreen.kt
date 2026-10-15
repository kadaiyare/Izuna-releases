package com.kadaiyare.izuna.ui.settings
import com.kadaiyare.izuna.BuildConfig

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kadaiyare.izuna.ui.components.MusicHeading
import com.kadaiyare.izuna.ui.components.MusicToolbar

@Composable
fun LastFmScreen(viewModel: LastFmViewModel, onBack: () -> Unit, onBrowser: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val delivery by viewModel.delivery.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    var confirmRetry by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val openBrowser by rememberUpdatedState(onBrowser)
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.browserRequests.collect { openBrowser(it) }
        }
    }
    val enabled = !state.loading && !state.busy && !working
    val canSend = state.configured && state.accountName != null && !state.storageProblem
    if (confirmRetry) AlertDialog(onDismissRequest = { confirmRetry = false },
        title = { Text("結果不明の曲を再送しますか？") },
        text = { Text("${delivery.uncertain}件はLast.fmで受け付けられている可能性があります。再送すると重複する場合があります。") },
        confirmButton = { TextButton(onClick = { confirmRetry = false; viewModel.retry(true) }, enabled = enabled && state.enabledAtMs > 0) { Text("再送する") } },
        dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text("キャンセル") } })
    Column(Modifier.fillMaxSize()) {
        MusicToolbar("Last.fm", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { MusicHeading("Last.fm", "アカウントの接続") }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(20.dp)) {
                        Text(when {
                            state.loading -> "接続情報を読み込み中…"
                            state.accountName != null -> "接続済み · ${state.accountName}"
                            state.storageProblem -> "接続情報を確認できません"
                            !state.configured -> "接続設定が未設定です"
                            state.pending -> "ブラウザでの許可を待っています"
                            else -> "未接続"
                        }, style = MaterialTheme.typography.titleMedium)
                        Text(when {
                            !state.configured -> if (BuildConfig.DEBUG) "このビルドではLast.fmの接続設定が必要です。"
                                else "このバージョンではLast.fm連携を利用できません。"
                            state.accountName != null -> "Scrobbleと再生中の曲の共有は、下の設定で有効にできます。"
                            state.pending -> "ブラウザでIzunaを許可し、この画面に戻って「許可を確認する」を押してください。"
                            else -> "Last.fmのブラウザ画面でログインして許可します。パスワードをIzunaへ入力する必要はありません。"
                        }, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.busy || working) Text("設定を確認中…", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall)
                        if (state.configured && !state.storageProblem && state.accountName == null) {
                            if (state.pending) {
                                Button(onClick = viewModel::confirm, enabled = enabled, modifier = Modifier.padding(top = 16.dp)) { Text("許可を確認する") }
                                TextButton(onClick = viewModel::connect, enabled = enabled) { Text("ブラウザをもう一度開く") }
                            } else Button(onClick = viewModel::connect, enabled = enabled, modifier = Modifier.padding(top = 16.dp)) {
                                Text("ブラウザで接続する")
                            }
                        }
                        if (state.accountName != null || state.pending || state.storageProblem) {
                            TextButton(onClick = viewModel::disconnect, enabled = enabled, modifier = Modifier.padding(top = 8.dp)) {
                                Text(if (state.storageProblem) "接続情報をリセット" else if (state.pending) "接続をキャンセル" else "接続を解除する")
                            }
                        }
                    }
                }
            }
            state.message?.let { message -> item {
                Text(message, Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        Row(Modifier.fillMaxWidth().toggleable(state.enabledAtMs > 0, enabled = enabled && canSend,
                            role = Role.Switch, onValueChange = viewModel::scrobbling).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Scrobbleを送信", style = MaterialTheme.typography.titleMedium)
                                Text(if (canSend) "再生中の曲も共有します。オンにした後で再生を始めた曲が対象です。履歴の記録もオンにしてください。" else "Last.fmに接続してから、Scrobbleと再生中の曲の共有を有効にできます。",
                                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = state.enabledAtMs > 0, onCheckedChange = null, enabled = enabled && canSend)
                        }
                        if (state.accountName != null) {
                            Text("送信待ち ${delivery.pending} · 処理中 ${delivery.sending}\n送信済み ${delivery.accepted} · 対象外 ${delivery.ignored + delivery.rejected} · 結果不明 ${delivery.uncertain}",
                                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (delivery.pending > 0) TextButton(onClick = { viewModel.retry(false) },
                                enabled = enabled && state.enabledAtMs > 0, modifier = Modifier.padding(horizontal = 12.dp)) { Text("送信待ちを再試行") }
                            if (delivery.uncertain > 0) TextButton(onClick = { confirmRetry = true },
                                enabled = enabled && state.enabledAtMs > 0, modifier = Modifier.padding(horizontal = 12.dp)) { Text("結果不明の曲を再送") }
                        }
                    }
                }
            }
            delivery.error?.let { message -> item { Text(message, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error) } }
            item {
                Text("曲名・アーティスト・アルバム・再生開始時刻・長さをLast.fmへ送ります。音源ファイルは送りません。\n再生中の曲の通知は上の件数に含みません。失敗した通知は再送しません。\nオフや接続解除で新しい送信を停止します。送信待ちは端末に残り、同じアカウントでオンにすると再開します。送信中の1件は完了する場合があります。Last.fmに表示済みの再生中の曲は、オフや一時停止ですぐには消えません。",
                    Modifier.padding(horizontal = 24.dp, vertical = 24.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
