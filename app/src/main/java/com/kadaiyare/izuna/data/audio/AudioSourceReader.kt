package com.kadaiyare.izuna.data.audio

import android.content.ContentResolver
import android.net.Uri
import com.kadaiyare.izuna.core.model.FlacStreamInfo
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioSourceReader(private val resolver: ContentResolver) {
    suspend fun read(uri: Uri): FlacStreamInfo? = withContext(Dispatchers.IO) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext null
        try {
            resolver.openInputStream(uri)?.use(::readFlacStreamInfo)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
