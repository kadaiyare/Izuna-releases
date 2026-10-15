package com.kadaiyare.izuna.discordruntime

import android.app.Activity
import android.system.Os
import android.system.OsConstants
import com.discord.socialsdk.DiscordSocialSdkInit

/** Loading is explicit and guarded before entering any SDK initialization code. */
class DiscordNative {
    external fun create(applicationId: Long): Long
    external fun publish(handle: Long, title: ByteArray, artist: ByteArray, listening: Boolean,
                         musicDetails: Boolean, startMs: Long, endMs: Long, imageUrl: ByteArray? = null,
                         statusDisplayType: Int = 0)
    external fun clear(handle: Long)
    external fun poll(handle: Long): IntArray
    external fun version(): String
    external fun destroy(handle: Long)

    companion object {
        private fun checkPageSize(allow16kb: Boolean) {
            val size = Os.sysconf(OsConstants._SC_PAGESIZE)
            check(size == 4096L || (size == 16384L && allow16kb)) { "Discord SDK disabled on this page size" }
        }
        fun prepare(activity: Activity) {
            checkPageSize(true)
            DiscordSocialSdkInit.setEngineActivity(activity)
            load(allow16kb = true)
        }
        fun load(allow16kb: Boolean = false) {
            checkPageSize(allow16kb)
            System.loadLibrary("discord_partner_sdk")
            System.loadLibrary("izuna_discord_runtime")
        }
    }
}
