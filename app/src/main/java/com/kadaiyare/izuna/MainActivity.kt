package com.kadaiyare.izuna

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kadaiyare.izuna.playback.PlaybackConnection
import com.kadaiyare.izuna.data.discord.DiscordPresence
import com.kadaiyare.izuna.ui.IzunaApp
import com.kadaiyare.izuna.ui.history.HistoryViewModel
import com.kadaiyare.izuna.ui.playlist.PlaylistViewModel
import com.kadaiyare.izuna.ui.library.LibraryViewModel
import com.kadaiyare.izuna.ui.settings.SettingsViewModel
import com.kadaiyare.izuna.ui.settings.LastFmViewModel
import com.kadaiyare.izuna.ui.settings.ListenBrainzViewModel
import com.kadaiyare.izuna.ui.player.LyricsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var library: LibraryViewModel
    private lateinit var history: HistoryViewModel
    private lateinit var playlists: PlaylistViewModel
    private lateinit var settings: SettingsViewModel
    private lateinit var lastFm: LastFmViewModel
    private lateinit var listenBrainz: ListenBrainzViewModel
    private lateinit var playback: PlaybackConnection
    private lateinit var lyrics: LyricsViewModel
    private var audioGranted by mutableStateOf(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioGranted = granted
        if (granted) library.scan() else library.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        library = ViewModelProvider(this)[LibraryViewModel::class.java]
        history = ViewModelProvider(this)[HistoryViewModel::class.java]
        playlists = ViewModelProvider(this)[PlaylistViewModel::class.java]
        settings = ViewModelProvider(this)[SettingsViewModel::class.java]
        lastFm = ViewModelProvider(this)[LastFmViewModel::class.java]
        listenBrainz = ViewModelProvider(this)[ListenBrainzViewModel::class.java]
        lyrics = ViewModelProvider(this)[LyricsViewModel::class.java]
        playback = PlaybackConnection(applicationContext)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Only the visible UI samples position; the service never polls for UI.
                while (true) {
                    playback.refreshPosition()
                    delay(if (lyrics.isVisible) 100 else 500)
                }
            }
        }
        setContent {
            IzunaApp(
                library, playback, history, playlists, settings, lastFm, listenBrainz, lyrics, audioGranted,
                onPermission = { permission.launch(Manifest.permission.READ_MEDIA_AUDIO) },
                onSystemSettings = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                },
                onBrowser = { url ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        lastFm.browserUnavailable()
                    }
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        playback.connect()
    }

    override fun onResume() {
        super.onResume()
        DiscordPresence.get(this).attach(this)
        val wasGranted = audioGranted
        audioGranted = checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioGranted && (!wasGranted || library.state.value.tracks.isEmpty())) {
            library.scan()
        } else if (!audioGranted) {
            library.permissionDenied()
        }
    }

    override fun onStop() {
        lyrics.hide()
        playback.disconnect()
        super.onStop()
    }
    override fun onDestroy() {
        DiscordPresence.get(this).detach(this)
        super.onDestroy()
    }
}
