package com.kadaiyare.izuna.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NowPlayingWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = WidgetAction.entries.firstOrNull { it != WidgetAction.Refresh && widgetActionName(it) == intent.action }
        if (action != null) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            // A stale or forged ID must not control the service.
            if (AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider == ComponentName(context, javaClass))
                updateAsync(context, action)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isNotEmpty()) updateAsync(context, WidgetAction.Refresh)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        updateAsync(context, WidgetAction.Refresh)
    }

    private fun updateAsync(context: Context, action: WidgetAction) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            try {
                val state = try { controlFromWidget(context.applicationContext, action) }
                catch (failure: kotlinx.coroutines.TimeoutCancellationException) { WidgetState(status = WidgetStatus.Unavailable) }
                catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { WidgetState(status = WidgetStatus.Unavailable) }
                NowPlayingWidgets.get(context).publish(state, force = true)
            } finally { pending?.finish(); scope.cancel() }
        }
    }
}
