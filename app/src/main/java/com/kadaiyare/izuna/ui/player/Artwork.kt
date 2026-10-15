package com.kadaiyare.izuna.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kadaiyare.izuna.ui.components.MusicIcon
import com.kadaiyare.izuna.ui.components.MusicSymbol
import com.kadaiyare.izuna.data.artwork.loadArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Artwork(uri: String?, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    val bitmap = produceState<Bitmap?>(null, uri) {
        value = null
        value = uri?.let { withContext(Dispatchers.IO) { loadArtwork(resolver, it) } }
    }.value
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(
        Brush.linearGradient(listOf(Color(0xFFB0ACCF), Color(0xFF716D98))),
    ), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides Color(0xFF403C61),
            ) {
                MusicIcon(MusicSymbol.Note, null, Modifier.fillMaxSize(0.32f))
            }
        }
    }
}
