package com.kadaiyare.izuna

import android.os.Bundle
import android.os.Process
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import com.kadaiyare.izuna.playback.PlaybackControllerPolicy
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackSecurityTest {
    @Test fun foreignControllersCannotReadOrControlEvenWithOurPackageName() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(instrumentation.targetContext).build()
            val session = MediaSession.Builder(instrumentation.targetContext, player).setId("security-check")
                .setCallback(PlaybackControllerPolicy).build()
            fun controller(name: String, uid: Int, trusted: Boolean) = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                name, uid, uid, 1, 1, trusted, Bundle.EMPTY, false)
            try {
                for (name in listOf("untrusted.example", instrumentation.targetContext.packageName)) {
                    val foreign = controller(name, Process.myUid() + 10_000, false)
                    assertFalse(PlaybackControllerPolicy.allows(foreign))
                    assertFalse(PlaybackControllerPolicy.onConnectAsync(session, foreign).get().isAccepted)
                }
                for (allowed in listOf(controller(instrumentation.targetContext.packageName, Process.myUid(), false),
                    controller("system.example", Process.myUid() + 10_000, true))) {
                    val result = PlaybackControllerPolicy.onConnectAsync(session, allowed).get()
                    assertTrue(result.isAccepted)
                    assertTrue(result.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE))
                }
            } finally { session.release(); player.release() }
        }
    }
}
