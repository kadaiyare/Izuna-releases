package com.kadaiyare.izuna.widget

import android.content.Context
import androidx.media3.common.Player

/** Service events update every placed widget even when the app UI has disconnected. */
internal class PlaybackWidgetRecorder(private val context: Context, private val player: Player) {
    private val widgets = NowPlayingWidgets.get(context)
    private var last = WidgetState.from(context, player)
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { publish() }
    }

    init { player.addListener(listener); publish() }

    private fun publish() {
        last = WidgetState.from(context, player)
        widgets.publish(last)
    }

    fun close() {
        player.removeListener(listener)
        widgets.publish(last.stopped())
    }
}
