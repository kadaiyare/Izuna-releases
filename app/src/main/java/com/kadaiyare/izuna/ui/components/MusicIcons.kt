package com.kadaiyare.izuna.ui.components

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small code-native symbols so controls do not depend on font glyphs. */
enum class MusicSymbol(val path: String, val filled: Boolean = false) {
    Play("M8,5 L19,12 L8,19 Z", true),
    Pause("M8,5 L8,19 M16,5 L16,19"),
    Previous("M5,5 L5,19 M19,5 L9,12 L19,19 Z", true),
    Next("M19,5 L19,19 M5,5 L15,12 L5,19 Z", true),
    Stop("M6,6 L18,6 L18,18 L6,18 Z", true),
    Queue("M4,6 L20,6 M4,12 L20,12 M4,18 L14,18"),
    More("M12,4 L12,4.1 M12,12 L12,12.1 M12,20 L12,20.1"),
    Shuffle("M3,6 L6,6 C11,6 13,18 18,18 L21,18 M18,15 L21,18 L18,21 M3,18 L6,18 C8,18 9,15 10,13 M14,8 C16,6 17,6 21,6 M18,3 L21,6 L18,9"),
    Repeat("M4,8 L4,6 L20,6 L17,3 M20,6 L17,9 M20,16 L20,18 L4,18 L7,21 M4,18 L7,15"),
    Back("M15,5 L8,12 L15,19"),
    Down("M5,9 L12,16 L19,9"),
    Search("M10.5,3 A7.5,7.5 0,1 0,10.5,18 A7.5,7.5 0,1 0,10.5,3 M16,16 L21,21"),
    Refresh("M20,8 A8,8 0,1 0,20,16 M20,3 L20,8 L15,8"),
    Settings("M10,3 L14,3 L14.6,5.4 L16.4,6.4 L18.8,5.7 L20.8,9.1 L19,10.8 L19,13.2 L20.8,14.9 L18.8,18.3 L16.4,17.6 L14.6,18.6 L14,21 L10,21 L9.4,18.6 L7.6,17.6 L5.2,18.3 L3.2,14.9 L5,13.2 L5,10.8 L3.2,9.1 L5.2,5.7 L7.6,6.4 L9.4,5.4 Z M12,8 A4,4 0,1 0,12,16 A4,4 0,1 0,12,8"),
    Close("M6,6 L18,18 M18,6 L6,18"),
    Note("M10,17 L10,5 L20,3 L20,15 M10,17 A3,3 0,1 1,7,14 A3,3 0,0 1,10,17 M20,15 A3,3 0,1 1,17,12 A3,3 0,0 1,20,15"),
}

@Composable
fun MusicIcon(symbol: MusicSymbol, description: String?, modifier: Modifier = Modifier) {
    val vector = remember(symbol) {
        ImageVector.Builder(symbol.name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = PathParser().parsePathString(symbol.path).toNodes(),
                fill = if (symbol.filled) SolidColor(Color.White) else null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = if (symbol == MusicSymbol.Pause) 3.5f else 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }
    Icon(vector, contentDescription = description, modifier = modifier)
}
