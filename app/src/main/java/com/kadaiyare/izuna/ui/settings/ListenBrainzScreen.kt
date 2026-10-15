package com.kadaiyare.izuna.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kadaiyare.izuna.ui.components.MusicHeading
import com.kadaiyare.izuna.ui.components.MusicToolbar

@Composable
fun ListenBrainzScreen(viewModel: ListenBrainzViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val delivery by viewModel.delivery.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    var root by rememberSaveable { mutableStateOf("https://api.listenbrainz.org") }
    // A token draft never enters saved instance state or a ViewModel's public state.
    var token by remember { mutableStateOf("") }
    var confirmRetry by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.account) { if (state.account != null) { token = ""; root = state.root ?: root } }
    val enabled = !state.loading && !state.busy && !working
    val connected = state.account != null && !state.storageProblem
    if (confirmRetry) AlertDialog(onDismissRequest = { confirmRetry = false },
        title = { Text("結果不明の曲を再送しますか？") },
        text = { Text("${delivery.uncertain}件はサーバーが受け付けている可能性があります。再送すると重複する場合があります。") },
        confirmButton = { TextButton(onClick = { confirmRetry = false; viewModel.retry(true) }, enabled = enabled && state.enabledAtMs > 0) { Text("再送する") } },
        dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text("キャンセル") } })
    Column(Modifier.fillMaxSize().imePadding()) {
        MusicToolbar("ListenBrainz互換サーバー", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { MusicHeading("ListenBrainz", "好きなサーバーに再生履歴を送信") }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(20.dp)) {
                        Text(when {
                            state.loading -> "接続情報を読み込み中…"
                            state.storageProblem -> "接続情報を確認できません"
                            connected -> "接続済み · ${state.account}"
                            else -> "未接続"
                        }, style = MaterialTheme.typography.titleMedium)
                        if (connected) {
                            Text(state.root.orEmpty(), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::disconnect, enabled = enabled) { Text("接続を解除する") }
                        } else if (state.storageProblem) {
                            TextButton(onClick = viewModel::disconnect, enabled = enabled) { Text("接続情報をリセット") }
                        } else {
                            Text("利用するサーバーのAPI URLとユーザートークンを入力してください。接続確認ではトークンだけを指定先へ送り、曲情報は送りません。",
                                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(root, { root = it }, Modifier.fillMaxWidth().padding(top = 16.dp),
                                label = { Text("API URL") }, singleLine = true, enabled = enabled,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                            Text("HTTPSのURLを指定してください。例: https://example.com/apis/listenbrainz/1",
                                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().padding(top = 12.dp),
                                label = { Text("ユーザートークン") }, singleLine = true, enabled = enabled,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                            Button(onClick = { viewModel.connect(root, token) }, enabled = enabled && root.isNotBlank() && token.isNotBlank(),
                                modifier = Modifier.padding(top = 16.dp)) { Text("接続を確認して保存") }
                        }
                        if (state.busy || working) Text("設定を確認中…", Modifier.padding(top = 12.dp))
                    }
                }
            }
            state.message?.let { item { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.bodySmall) } }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        Row(Modifier.fillMaxWidth().toggleable(state.enabledAtMs > 0, enabled = enabled && connected,
                            role = Role.Switch, onValueChange = viewModel::scrobbling).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Scrobbleを送信", style = MaterialTheme.typography.titleMedium)
                                Text("再生中の曲も共有します。オンにした後で再生を始めた曲が対象です。履歴の記録もオンにしてください。",
                                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(state.enabledAtMs > 0, null, enabled = enabled && connected)
                        }
                        if (connected) {
                            Text("送信待ち ${delivery.pending} · 処理中 ${delivery.sending}\n送信済み ${delivery.accepted} · 対象外 ${delivery.rejected} · 結果不明 ${delivery.uncertain}",
                                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
                            if (delivery.pending > 0) TextButton(onClick = { viewModel.retry(false) }, enabled = enabled && state.enabledAtMs > 0) { Text("送信待ちを再試行") }
                            if (delivery.uncertain > 0) TextButton(onClick = { confirmRetry = true }, enabled = enabled && state.enabledAtMs > 0) { Text("結果不明の曲を再送") }
                        }
                    }
                }
            }
            delivery.error?.let { item { Text(it, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error) } }
            item { Text("曲名・アーティスト・アルバム・再生開始時刻・長さを指定したサーバーへ送ります。音源ファイルや画像は送りません。\nLast.fmとは別々に送信できます。オフや接続解除で新しい送信を止めます。送信中の1件は完了する場合があります。\n送信待ちは端末に残り、同じURL・アカウントで再接続してオンにすると再開します。再生中の曲の通知は失敗しても再送せず、停止時にサーバーの表示がすぐ消えることは保証しません。",
                Modifier.padding(horizontal = 24.dp, vertical = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
