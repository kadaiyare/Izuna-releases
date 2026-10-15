package com.kadaiyare.izuna.widget

import android.appwidget.AppWidgetManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.kadaiyare.izuna.data.artwork.loadArtwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Process-local display projection; the service Player remains the sole playback authority. */
internal class NowPlayingWidgets private constructor(context: Context) {
    private val app = context.applicationContext as Application
    private val manager = AppWidgetManager.getInstance(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updates = WidgetArtworkUpdates(scope,
        { uri -> withContext(Dispatchers.IO) { loadArtwork(app.contentResolver, uri, maxSide = 192) } },
        { state, bitmap ->
            for (id in ids()) {
                try { manager.updateAppWidget(id, nowPlayingWidgetViews(app, id, state, bitmap)) }
                catch (_: Exception) { Log.w("IzunaWidget", "Unable to update widget") }
            }
        })

    fun ids(): IntArray = manager.getAppWidgetIds(ComponentName(app, NowPlayingWidgetProvider::class.java))
    fun publish(state: WidgetState, force: Boolean = false) { if (ids().isNotEmpty()) updates.update(state, force) }

    companion object {
        private var instance: NowPlayingWidgets? = null
        // Called only on the main thread, from Player listeners and widget receivers.
        fun get(context: Context): NowPlayingWidgets = instance ?: NowPlayingWidgets(context).also { instance = it }
    }
}
