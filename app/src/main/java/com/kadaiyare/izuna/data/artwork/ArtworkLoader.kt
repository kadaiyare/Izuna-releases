package com.kadaiyare.izuna.data.artwork

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.net.toUri

private val artworkCache = object : LruCache<String, Bitmap>(8 * 1_024 * 1_024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Call on IO. Widgets use a smaller decode to keep RemoteViews well below the Binder limit. */
internal fun loadArtwork(resolver: ContentResolver, value: String, maxSide: Int = 512): Bitmap? {
    require(maxSide in 1..512)
    val key = "$maxSide:$value"
    artworkCache.get(key)?.let { return it }
    return try {
        val uri = value.toUri()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxSide) options.inSampleSize *= 2
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?.also { artworkCache.put(key, it) }
    } catch (_: Exception) { null }
}
