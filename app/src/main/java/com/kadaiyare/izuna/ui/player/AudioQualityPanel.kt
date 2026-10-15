package com.kadaiyare.izuna.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.kadaiyare.izuna.core.model.FlacStreamInfo
import com.kadaiyare.izuna.data.audio.AudioSourceReader

private data class SourceQuality(val loading: Boolean = true, val info: FlacStreamInfo? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioQualityPanel(mediaId: String) {
    val resolver = LocalContext.current.contentResolver
    val reader = remember(resolver) { AudioSourceReader(resolver) }
    val quality by produceState(SourceQuality(), mediaId, reader) {
        value = SourceQuality()
        value = SourceQuality(loading = false, info = reader.read(mediaId.toUri()))
    }
    var detailsOpen by rememberSaveable(mediaId) { mutableStateOf(false) }
    val info = quality.info
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)
        .clickable(role = Role.Button, onClickLabel = "音源の詳細を表示", onClick = { detailsOpen = true }),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (info != null) {
            Text(if (info.isHiRes) "FLAC · ロスレス · ハイレゾ" else "FLAC · ロスレス",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${info.bitDepth} bit · ${info.sampleRateLabel} · ${info.channelsLabel} ›",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(if (quality.loading) "音源の情報を読み込み中…" else "音源の情報を取得できません ›",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (detailsOpen) {
        ModalBottomSheet(onDismissRequest = { detailsOpen = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("音源の品質", style = MaterialTheme.typography.headlineSmall)
                if (info != null) {
                    QualityDetail("コーデック", "FLAC")
                    QualityDetail("圧縮方式", "ロスレス（可逆圧縮）")
                    QualityDetail("ビット深度", "${info.bitDepth} bit")
                    QualityDetail("サンプルレート", info.sampleRateLabel)
                    QualityDetail("チャンネル", "${info.channelsLabel}（${info.channels} ch）")
                    QualityDetail("解像度", if (info.isHiRes) "ハイレゾ" else "ハイレゾ対象外")
                    Text("ハイレゾは音源の数値で判定します。44.1 kHz / 16 bit以上で、48 kHzまたは16 bitを超える音源が対象です。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(if (quality.loading) "音源のヘッダーを読み込んでいます。" else "ファイルを読み取れない、または対応するFLACヘッダーがありません。",
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text("表示は音源ファイルの情報です。Bluetoothの伝送コーデックやDACへの実際の出力形式は未計測です。録音原本の品質やアップコンバートの有無は判定しません。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QualityDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
