package com.kadaiyare.izuna.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.kadaiyare.izuna.MainActivity
import com.kadaiyare.izuna.R

internal fun widgetControlIntent(context: Context, id: Int, action: WidgetAction): PendingIntent = PendingIntent.getBroadcast(
    context, id,
    Intent(context, NowPlayingWidgetProvider::class.java).setAction(widgetActionName(action))
        .setData("izuna-widget://$id/${action.name}".toUri())
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

internal fun widgetActionName(action: WidgetAction) = "com.kadaiyare.izuna.widget.${action.name}"

internal fun nowPlayingWidgetViews(context: Context, id: Int, state: WidgetState, artwork: Bitmap?): RemoteViews {
    val open = PendingIntent.getActivity(context, id,
        Intent(context, MainActivity::class.java).setData("izuna-widget://$id/open".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
        setTextViewText(R.id.widget_title, state.title ?: context.getString(R.string.app_name))
        setTextViewText(R.id.widget_artist, state.artist ?: context.getString(R.string.widget_choose_track))
        setTextViewText(R.id.widget_status, context.getString(when (state.status) {
            WidgetStatus.Idle -> R.string.widget_status_idle
            WidgetStatus.Playing -> R.string.widget_status_playing
            WidgetStatus.Paused -> R.string.widget_status_paused
            WidgetStatus.Buffering -> R.string.widget_status_buffering
            WidgetStatus.Ended -> R.string.widget_status_ended
            WidgetStatus.Error -> R.string.widget_status_error
            WidgetStatus.Permission -> R.string.widget_status_permission
            WidgetStatus.Unavailable -> R.string.widget_status_unavailable
        }))
        setOnClickPendingIntent(R.id.widget_open, open)
        setImageViewResource(R.id.widget_toggle, if (state.pauseAction) R.drawable.widget_pause else R.drawable.widget_play)
        setContentDescription(R.id.widget_toggle, context.getString(when {
            !state.canControl -> R.string.widget_open_app
            state.pauseAction -> R.string.widget_pause
            else -> R.string.widget_play
        }))
        setOnClickPendingIntent(R.id.widget_toggle, if (state.canControl) widgetControlIntent(context, id, WidgetAction.Toggle) else open)
        for ((view, available, action) in listOf(Triple(R.id.widget_previous, state.previous, WidgetAction.Previous),
            Triple(R.id.widget_next, state.next, WidgetAction.Next))) {
            val enabled = available && state.canControl
            setBoolean(view, "setEnabled", enabled)
            setFloat(view, "setAlpha", if (enabled) 1f else 0.3f)
            setOnClickPendingIntent(view, widgetControlIntent(context, id, action))
        }
        if (artwork == null) setImageViewResource(R.id.widget_artwork, R.drawable.widget_note)
        else setImageViewBitmap(R.id.widget_artwork, artwork)
        val padding = if (artwork == null) (16 * context.resources.displayMetrics.density).toInt() else 0
        setViewPadding(R.id.widget_artwork, padding, padding, padding, padding)
    }
}
