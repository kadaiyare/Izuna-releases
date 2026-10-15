package com.kadaiyare.izuna.playback

import android.content.Context
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.Player
import com.kadaiyare.izuna.data.audio.readFlacScrobbleTags
import com.kadaiyare.izuna.data.discord.DiscordPresence
import com.kadaiyare.izuna.data.discord.PresenceSong
import com.kadaiyare.izuna.data.discord.CoverLookupSession
import com.kadaiyare.izuna.data.discord.PublicCoverProvider
import com.kadaiyare.izuna.data.discord.DiscordDisplaySettings
import com.kadaiyare.izuna.data.settings.SettingsRepository
import com.kadaiyare.izuna.data.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow

/** Event-driven presence; neither tag IO nor the SDK can block the audio thread. */
internal class DiscordPlaybackRecorder(context: Context, private val player: Player,
    private val presence: DiscordPresence = DiscordPresence.get(context),
    settings: Flow<AppSettings> = SettingsRepository.get(context).data,
) : AutoCloseable {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val covers = CoverLookupSession(scope, PublicCoverProvider.shared) { presence.update(it) }
    private var enabled = false
    private var coverSearch = false
    private var display = DiscordDisplaySettings()
    private var revision = 0L
    private var reading: Job? = null
    private var closed = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) refresh()
        }
    }
    init {
        player.addListener(listener)
        scope.launch {
            settings.catch { emit(AppSettings(discordSharing = false)) }.collect { settings ->
                if (enabled != settings.discordSharing || coverSearch != settings.discordCoverLookup || display != settings.discordDisplay) {
                    enabled = settings.discordSharing
                    coverSearch = settings.discordCoverLookup
                    display = settings.discordDisplay
                    presence.setEnabled(enabled)
                    refresh()
                }
            }
        }
    }
    private fun refresh() {
        val request = ++revision
        reading?.cancel()
        covers.cancel()
        presence.update(null)
        if (closed || !enabled || !presence.state.value.available || !player.isPlaying) return
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        reading = scope.launch {
            val tags = try { withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri.toUri())?.use(::readFlacScrobbleTags) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
            if (request != revision || closed || !enabled || !player.isPlaying || player.currentMediaItem?.localConfiguration?.uri?.toString() != uri) return@launch
            if (tags?.valid() != true) {
                presence.update(null, "曲名・アーティストのタグがないため、この曲は共有しません。")
                return@launch
            }
            val text = display.format(tags.title, tags.artist, tags.album)
            covers.start(tags, coverSearch) {
                if (request != revision || closed || !enabled || !player.isPlaying ||
                    player.currentMediaItem?.localConfiguration?.uri?.toString() != uri) null
                else PresenceSong(text.title, text.subtitle, player.currentPosition,
                    player.duration, SystemClock.elapsedRealtime(), compactDisplay = display.compactDisplay)
            }
        }
    }
    override fun close() {
        closed = true; revision++
        player.removeListener(listener)
        covers.cancel()
        scope.cancel()
        presence.update(null)
    }
}
