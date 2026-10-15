package com.kadaiyare.izuna.discordprobe

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Standalone, manual RPC verification with fixed fictional tracks. */
class ProbeActivity : ComponentActivity() {
    private lateinit var probe: ProbeViewModel
    private var discord by mutableStateOf("確認中…")
    private var browserMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        probe = ViewModelProvider(this)[ProbeViewModel::class.java]
        probe.initialize(this)
        setContent {
            val state by probe.state.collectAsState()
            var listening by rememberSaveable { mutableStateOf(false) }
            var musicDetails by rememberSaveable { mutableStateOf(false) }
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color(0xFF080808), surface = Color(0xFF171717), primary = Color(0xFF9DBFFF))) {
                Scaffold { insets ->
                    LazyColumn(Modifier.fillMaxSize().padding(insets),
                        contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        item {
                            Text("Discord\n検証", Modifier.padding(top = 36.dp), style = MaterialTheme.typography.displayMedium)
                            Text("曲情報の表示・更新・消去", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item {
                            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("準備状況", style = MaterialTheme.typography.titleLarge)
                                    Text("Discordアプリ: $discord")
                                    Text("Application ID: ${when {
                                        !BuildConfig.DISCORD_ID_SET -> "未設定"
                                        !BuildConfig.DISCORD_ID_VALID -> "形式を確認してください"
                                        else -> "設定済み"
                                    }}")
                                    Text("Social SDK: ${state.sdkVersion ?: "準備中"}")
                                    if (BuildConfig.DIAGNOSE_16KB) Text("16 KB診断ビルド · クラッシュする可能性があります", color = MaterialTheme.colorScheme.error)
                                    Text(state.message,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item {
                            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("端末", style = MaterialTheme.typography.titleLarge)
                                    Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                                    Text("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                                    Text("ページサイズ: ${Os.sysconf(OsConstants._SC_PAGESIZE)} bytes")
                                }
                            }
                        }
                        item {
                            Text("プロフィール表示の検証", style = MaterialTheme.typography.titleLarge)
                            Text("表示ボタンを押すと、架空の曲名とアーティスト名をDiscordの公開プロフィールへ送ります。端末のDiscordへログインしてから試してください。",
                                Modifier.padding(top = 10.dp))
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Listeningタイプを試す", Modifier.weight(1f))
                                Switch(checked = listening, onCheckedChange = { listening = it }, enabled = !state.busy)
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("アプリ名と時間を試す", Modifier.weight(1f))
                                Switch(checked = musicDetails, onCheckedChange = { musicDetails = it }, enabled = !state.busy)
                            }
                            Text("オンではアプリ名「Izuna」と、架空の3分の曲を45秒から進める時間情報を送ります。切替だけでは送信せず、下の表示ボタンで反映します。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { probe.publish(false, listening, musicDetails) }, enabled = state.ready,
                                modifier = Modifier.fillMaxWidth()) { Text("曲Aを表示") }
                            Button(onClick = { probe.publish(true, listening, musicDetails) }, enabled = state.ready,
                                modifier = Modifier.fillMaxWidth()) { Text("曲Bへ更新") }
                            Button(onClick = probe::clear, enabled = state.ready, modifier = Modifier.fillMaxWidth()) { Text("消去を要求") }
                            Text("架空の再生操作", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge)
                            state.demo?.let { demo ->
                                val seconds = demo.positionAt(android.os.SystemClock.elapsedRealtime()) / 1000
                                Text("曲${if (demo.secondTrack) "B" else "A"} · ${if (demo.isPlaying) "進行中" else "一時停止"} · 操作時の位置 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} / 3:00")
                            } ?: Text("曲を表示すると操作できます")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = probe::pause, enabled = state.ready && state.demo?.isPlaying == true, modifier = Modifier.weight(1f)) { Text("一時停止") }
                                Button(onClick = probe::resume, enabled = state.ready && state.demo?.isPlaying == false, modifier = Modifier.weight(1f)) { Text("再開") }
                            }
                            Button(onClick = { probe.seek(90_000) }, enabled = state.ready && state.demo != null, modifier = Modifier.fillMaxWidth()) { Text("1:30へシーク") }
                            Button(onClick = { probe.seek(177_000) }, enabled = state.ready && state.demo != null, modifier = Modifier.fillMaxWidth()) { Text("残り3秒へシーク") }
                            Text("日本語・絵文字を含む固定タグを使います。音楽は実際には再生しません。一時停止・終端・消去では表示の消去を要求し、再開・再生中のシークで時間を更新します。停止中のシークは送信しません。画面回転では状態を保ち、終了時にも消去を要求します。強制停止やOSによる終了後に表示が残るかは、Discord側で確認してください。",
                                Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("現在のSDKは16 KBページ対応の検査が未通過です。4 KB端末向けの検証用で、本体への導入は保留しています。",
                                Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item {
                            Button(onClick = ::refresh, modifier = Modifier.fillMaxWidth()) { Text("環境を再確認") }
                            TextButton(onClick = ::openPortal, modifier = Modifier.fillMaxWidth()) { Text("Developer Portalを開く") }
                            TextButton(onClick = ::finish, modifier = Modifier.fillMaxWidth()) { Text("検証を終了") }
                            browserMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        probe.finishIfDue()
        discord = try {
            val info = packageManager.getPackageInfo("com.discord", PackageManager.PackageInfoFlags.of(0))
            if (info.applicationInfo?.enabled == true) "インストール済み (${info.versionName.orEmpty()}) · ログイン状態は未確認"
            else "インストール済み・無効"
        } catch (_: PackageManager.NameNotFoundException) { "未インストール" }
        catch (_: SecurityException) { "確認できません" }
    }

    private fun openPortal() {
        browserMessage = null
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "https://discord.com/developers/applications".toUri()))
        } catch (_: ActivityNotFoundException) { browserMessage = "対応するブラウザが見つかりません。" }
    }
}
