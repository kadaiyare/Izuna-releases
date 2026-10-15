package com.kadaiyare.izuna.data.discord

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.kadaiyare.izuna.BuildConfig
import com.kadaiyare.izuna.discordruntime.DiscordNative
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface PresenceTransport {
    fun create(): Long
    fun publish(handle: Long, title: String, artist: String, start: Long, end: Long, imageUrl: String?, compactDisplay: DiscordCompactDisplay)
    fun clear(handle: Long)
    fun poll(handle: Long): IntArray
    fun destroy(handle: Long)
}

private class SdkTransport : PresenceTransport {
    private val native = DiscordNative()
    override fun create(): Long {
        check(native.version() == BuildConfig.DISCORD_EXPECTED_VERSION)
        return native.create(BuildConfig.DISCORD_APPLICATION_ID)
    }
    override fun publish(handle: Long, title: String, artist: String, start: Long, end: Long, imageUrl: String?, compactDisplay: DiscordCompactDisplay) =
        native.publish(handle, title.toByteArray(Charsets.UTF_8), artist.toByteArray(Charsets.UTF_8), true, true, start, end,
            imageUrl?.toByteArray(Charsets.UTF_8), compactDisplay.sdkValue)
    override fun clear(handle: Long) = native.clear(handle)
    override fun poll(handle: Long) = native.poll(handle)
    override fun destroy(handle: Long) = native.destroy(handle)
}

data class DiscordPresenceState(val available: Boolean, val message: String)

/** Captured on the Player thread; the native worker never reads Player. */
internal data class PresenceSong(val title: String, val artist: String, val positionMs: Long,
                                 val durationMs: Long, val capturedAt: Long, val imageUrl: String? = null,
                                 val compactDisplay: DiscordCompactDisplay = DiscordCompactDisplay.APP_NAME) {
    fun timestamps(now: Long, elapsed: Long): Pair<Long, Long> {
        val delta = (elapsed - capturedAt).coerceAtLeast(0)
        if (positionMs < 0 || durationMs <= 0 || positionMs >= durationMs || delta >= durationMs - positionMs) return 0L to 0L
        val position = positionMs + delta
        val remaining = durationMs - position
        if (now <= position || now > Long.MAX_VALUE - remaining) return 0L to 0L
        return (now - position) to (now + remaining)
    }
    fun ended(elapsed: Long) = durationMs > 0 && positionMs >= 0 &&
        (positionMs >= durationMs || (elapsed - capturedAt).coerceAtLeast(0) >= durationMs - positionMs)
}

/** Only the main thread changes desired state; all SDK operations use one worker. */
class DiscordPresence internal constructor(
    private val pageSize: Long,
    private val configured: Boolean,
    private val installed: () -> Boolean,
    private val prepare: (Activity) -> Unit,
    private val factory: () -> PresenceTransport,
) {
    private val main = Handler(Looper.getMainLooper())
    private val revision = AtomicLong()
    private var activity = WeakReference<Activity>(null)
    private var enabled = false
    private var loaded = false
    private var fatal = false
    private var worker: Handler? = null
    private val mutableState = MutableStateFlow(environment())
    val state = mutableState.asStateFlow()
    // Native-worker fields. No client or SDK thread exists until an allowed publish.
    private var native: PresenceTransport? = null
    private var handle = 0L
    private var activeRevision = 0L
    private var pendingAt = 0L
    private var clearingUntil = 0L
    private var lastStatus = -1
    private var song: PresenceSong? = null

    private fun environment(): DiscordPresenceState = when {
        !allowedPageSize() -> DiscordPresenceState(false, "この端末ではDiscord連携を利用できません。音楽の再生は通常どおり使えます。")
        !configured -> DiscordPresenceState(false, "Discord連携の設定が用意されていません。")
        !installed() -> DiscordPresenceState(false, "Discordアプリをインストールし、ログインしてください。")
        else -> DiscordPresenceState(true, "共有はオフです。")
    }
    private fun allowedPageSize() = pageSize == 4096L || pageSize == 16384L

    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
        val environment = environment()
        if (!environment.available) { setEnabled(false); mutableState.value = environment; return }
        if (!enabled && !fatal) mutableState.value = environment
        if (loaded && !fatal) try { prepare(activity) } catch (_: Exception) { fail() } catch (_: LinkageError) { fail() }
    }
    fun detach(activity: Activity) { if (this.activity.get() === activity) this.activity.clear() }

    internal fun setEnabled(enabled: Boolean) {
        if (enabled && fatal) return
        val allowed = enabled && environment().available
        if (this.enabled == allowed) return
        this.enabled = allowed
        if (!allowed) update(null)
        else mutableState.value = DiscordPresenceState(true, "再生中の曲情報・再生位置を表示設定に合わせて共有します。")
    }

    internal fun update(wanted: PresenceSong?, clearMessage: String? = null) {
        val request = revision.incrementAndGet()
        if (!enabled || wanted == null || !environment().available) {
            if (worker == null) return
            worker?.post { if (revision.get() == request) clearOnWorker(request, if (enabled) clearMessage ?: "再生停止中です。表示の消去を要求しました。" else "共有はオフです。表示の消去を要求しました。") }
            return
        }
        if (fatal) return
        if (!loaded) {
            val currentActivity = activity.get()
            if (currentActivity == null) { mutableState.value = DiscordPresenceState(true, "Discord連携を始めるにはIzunaの画面を開いてください。"); return }
            try { prepare(currentActivity); loaded = true }
            catch (_: Exception) { fail(); return }
            catch (_: LinkageError) { fail(); return }
        }
        val handler = worker ?: Handler(HandlerThread("IzunaDiscordPresence").apply { start() }.looper).also { worker = it }
        mutableState.value = DiscordPresenceState(true, "曲情報を共有中…")
        handler.post {
            if (revision.get() != request) return@post
            try {
                val transport = native ?: factory().also { native = it }
                if (handle == 0L) handle = transport.create().also { check(it != 0L) }
                if (wanted.ended(SystemClock.elapsedRealtime())) { clearOnWorker(request, "曲が終了しました。表示の消去を要求しました。"); return@post }
                activeRevision = request
                song = wanted
                clearingUntil = 0
                val times = wanted.timestamps(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                transport.publish(handle, wanted.title, wanted.artist, times.first, times.second, wanted.imageUrl, wanted.compactDisplay)
                pendingAt = SystemClock.elapsedRealtime()
                lastStatus = -1
                schedulePoll()
            } catch (_: Exception) { fail() } catch (_: LinkageError) { fail() }
        }
    }

    private fun status(request: Long, message: String) {
        main.post { if (revision.get() == request && !fatal) mutableState.value = DiscordPresenceState(true, message) }
    }
    private fun clearOnWorker(request: Long, message: String) {
        try {
            activeRevision = request
            song = null; pendingAt = 0
            if (handle != 0L) {
                native?.clear(handle)
                lastStatus = 4
                clearingUntil = SystemClock.elapsedRealtime() + 500
                schedulePoll()
            }
            status(request, message)
        } catch (_: Exception) { fail() } catch (_: LinkageError) { fail() }
    }
    private fun schedulePoll() { worker?.removeCallbacks(poll); worker?.postDelayed(poll, 50) }
    private val poll = object : Runnable {
        override fun run() {
            if (handle == 0L) return
            try {
                val fields = native!!.poll(handle)
                val now = SystemClock.elapsedRealtime()
                if (clearingUntil != 0L && now >= clearingUntil) {
                    native!!.destroy(handle); handle = 0; clearingUntil = 0
                    return
                }
                if (activeRevision == revision.get() && song != null) {
                    if (fields[0] != lastStatus) {
                        lastStatus = fields[0]
                        if (fields[0] == 2) { pendingAt = 0; status(activeRevision, "Discordへ曲情報を送りました。プロフィールで確認できます。") }
                        else if (fields[0] == 3) { clearOnWorker(activeRevision, "Discordへ送信できませんでした。消去を要求しました。次の再生操作で再度試せます。"); return }
                    }
                    if (pendingAt != 0L && now - pendingAt >= 15_000) { clearOnWorker(activeRevision, "Discordから応答がありません。消去を要求しました。"); return }
                    if (song!!.ended(now)) { clearOnWorker(activeRevision, "曲が終了しました。表示の消去を要求しました。"); return }
                }
                schedulePoll()
            } catch (_: Exception) { fail() } catch (_: LinkageError) { fail() }
        }
    }
    private fun fail() {
        main.post {
            fatal = true
            revision.incrementAndGet()
            mutableState.value = DiscordPresenceState(allowedPageSize() && configured, "Discord連携を停止しました。音楽はそのまま再生できます。再度試すにはIzunaを開き直してください。")
            worker?.post {
                worker?.removeCallbacks(poll)
                if (handle != 0L) {
                    try { native?.clear(handle) } catch (_: Exception) { } catch (_: LinkageError) { }
                    try { native?.destroy(handle) } catch (_: Exception) { } catch (_: LinkageError) { }
                    handle = 0
                }
            }
        }
    }

    /** Ends an owner without leaving test/native callbacks or a client alive. */
    internal fun close() {
        enabled = false
        revision.incrementAndGet()
        worker?.removeCallbacksAndMessages(null)
        worker?.post {
            if (handle != 0L) {
                try { native?.clear(handle); native?.poll(handle); native?.destroy(handle) }
                catch (_: Exception) { } catch (_: LinkageError) { }
                handle = 0
            }
            worker?.looper?.quitSafely()
        }
        activity.clear()
    }

    companion object {
        @Volatile private var instance: DiscordPresence? = null
        fun get(context: Context): DiscordPresence = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                DiscordPresence(Os.sysconf(OsConstants._SC_PAGESIZE), BuildConfig.DISCORD_ID_VALID, {
                    try { app.packageManager.getApplicationInfo("com.discord", PackageManager.ApplicationInfoFlags.of(0)).enabled }
                    catch (_: PackageManager.NameNotFoundException) { false } catch (_: SecurityException) { false }
                }, DiscordNative::prepare, ::SdkTransport)
            }.also { instance = it }
        }
    }
}
