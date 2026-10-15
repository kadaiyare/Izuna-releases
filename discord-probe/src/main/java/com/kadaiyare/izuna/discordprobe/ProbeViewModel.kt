package com.kadaiyare.izuna.discordprobe

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.lifecycle.AndroidViewModel
import com.discord.socialsdk.DiscordSocialSdkInit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class ProbeState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val sdkVersion: String? = null,
    val message: String = "SDKを準備中…",
    val demo: DemoPlayback? = null,
)

/** Retains one native client across rotation. It never starts playback or connects with OAuth. */
class ProbeViewModel private constructor(
    application: Application,
    private val native: ProbeTransport,
    private val prepare: (Activity) -> Unit,
    private val available: (() -> Boolean)?,
    private val elapsed: () -> Long,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, DiscordNative(), {
        DiscordSocialSdkInit.setEngineActivity(it)
        DiscordNative.load()
    }, null, SystemClock::elapsedRealtime)

    internal constructor(application: Application, transport: ProbeTransport, available: () -> Boolean = { true }, elapsed: () -> Long = SystemClock::elapsedRealtime) :
        this(application, transport, {}, available, elapsed)

    private val mutableState = MutableStateFlow(ProbeState())
    internal val state = mutableState.asStateFlow()
    private val closed = AtomicBoolean(false)
    private val revision = AtomicLong()
    private val main = Handler(Looper.getMainLooper())
    private var worker: Handler? = null
    private var thread: HandlerThread? = null
    // These fields belong only to the native worker.
    private var handle = 0L
    private var pendingAt = 0L
    private var lastStatus = -1
    private var activeRevision = 0L
    private var sentDemo: DemoPlayback? = null
    private var endPostedRevision = -1L

    fun initialize(activity: Activity) {
        if (closed.get()) return
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        if (pageSize != 4096L && !(pageSize == 16384L && BuildConfig.DIAGNOSE_16KB)) {
            mutableState.value = ProbeState(message = "このSDKの16 KB対応検査は未通過です。現在は4 KB端末だけで検証してください。")
            return
        }
        if (!BuildConfig.DISCORD_ID_VALID) {
            mutableState.value = ProbeState(message = "local.propertiesへ有効なdiscord.applicationIdを設定してください。")
            return
        }
        try {
            prepare(activity)
            if (worker != null) return
            thread = HandlerThread("IzunaDiscordProbe").apply { start() }
            worker = Handler(thread!!.looper).also { handler ->
                handler.post {
                    try {
                        handle = native.create(BuildConfig.DISCORD_APPLICATION_ID)
                        check(handle != 0L)
                        mutableState.value = ProbeState(ready = true, sdkVersion = native.version(), message =
                            if (pageSize == 16384L) "16 KB診断でSDKを読み込みました。Clientの操作や終了も確認してください。対応完了とは判定しません。"
                            else "SDKを読み込みました。表示ボタンで検証を始めます。")
                        handler.post(poll)
                    } catch (_: Exception) { nativeFailure() }
                    catch (_: LinkageError) { nativeFailure() }
                }
            }
        } catch (_: Exception) { nativeFailure() }
        catch (_: LinkageError) { nativeFailure() }
    }

    internal fun publish(secondTrack: Boolean, listening: Boolean, musicDetails: Boolean = false) {
        if (!canSend()) return
        val demo = DemoPlayback(secondTrack, listening, musicDetails, elapsed())
        mutableState.update { it.copy(demo = demo) }
        send(demo)
    }

    private fun canSend(): Boolean {
        if (!state.value.ready || closed.get()) return false
        val installed = available?.invoke() ?: try {
            getApplication<Application>().packageManager.getApplicationInfo("com.discord", PackageManager.ApplicationInfoFlags.of(0)).enabled
        } catch (_: PackageManager.NameNotFoundException) { false }
        catch (_: SecurityException) { false }
        if (!installed) {
            mutableState.update { it.copy(message = "公式Discordアプリをインストールし、ログインしてから表示を試してください。") }
            return false
        }
        return true
    }

    internal fun pause() {
        val demo = state.value.demo?.takeIf { it.isPlaying } ?: return
        if (!state.value.ready || closed.get()) return
        mutableState.update { it.copy(demo = demo.pause(elapsed())) }
        requestClear("一時停止: ")
    }

    internal fun resume() {
        val demo = state.value.demo?.takeIf { !it.isPlaying } ?: return
        if (!canSend()) return
        if (demo.positionMs >= DemoPlayback.DURATION_MS) { clear(); return }
        val resumed = demo.resume(elapsed())
        mutableState.update { it.copy(demo = resumed) }
        send(resumed)
    }

    internal fun seek(positionMs: Long) {
        val demo = state.value.demo ?: return
        if (!state.value.ready || closed.get() || (demo.isPlaying && !canSend())) return
        val sought = demo.seek(positionMs, elapsed())
        mutableState.update { it.copy(demo = sought) }
        if (sought.positionMs >= DemoPlayback.DURATION_MS) clear()
        else if (sought.isPlaying) send(sought)
        // A paused seek must not publish. Resume is the next explicit sharing operation.
    }

    internal fun finishIfDue() {
        val demo = state.value.demo ?: return
        if (demo.isPlaying && demo.positionAt(elapsed()) >= DemoPlayback.DURATION_MS) {
            mutableState.update { it.copy(demo = null) }
            requestClear("曲の終端: ")
        }
    }

    private fun send(demo: DemoPlayback) {
        val request = revision.incrementAndGet()
        mutableState.update { it.copy(busy = true, message = "${if (demo.secondTrack) "曲B" else "曲A"}を送信中… (${if (demo.listening) "Listening" else "Playing"})") }
        worker?.post {
            if (closed.get() || handle == 0L || revision.get() != request || !state.value.ready) return@post
            activeRevision = request
            try {
                // Include time spent waiting for the native worker; never send an already-ended track.
                val position = demo.positionAt(elapsed())
                if (position >= DemoPlayback.DURATION_MS) {
                    native.clear(handle)
                    pendingAt = 0L; lastStatus = 4; sentDemo = null
                    postStatus(request, "曲の終端: 消去を要求しました。Discord側で確認してください。", stopDemo = true)
                    return@post
                }
                val timestamps = if (demo.musicDetails) PresenceTimestamps.fromPlayback(System.currentTimeMillis(), position, DemoPlayback.DURATION_MS) else null
                native.publish(handle, (if (demo.secondTrack) "Izuna Demo B · 検証🌙" else "Izuna Demo A · 検証🎵").toByteArray(Charsets.UTF_8),
                    "Izuna Tests".toByteArray(Charsets.UTF_8), demo.listening, demo.musicDetails,
                    timestamps?.startMs ?: 0, timestamps?.endMs ?: 0)
                sentDemo = demo
                pendingAt = elapsed()
                lastStatus = -1
            } catch (_: Exception) { nativeFailure() }
            catch (_: LinkageError) { nativeFailure() }
        }
    }

    internal fun clear() {
        if (!state.value.ready || closed.get()) return
        mutableState.update { it.copy(demo = null) }
        requestClear()
    }

    private fun requestClear(reason: String = "") {
        val request = revision.incrementAndGet()
        mutableState.update { it.copy(busy = false, message = "${reason}消去を要求中…") }
        worker?.post {
            if (closed.get() || handle == 0L || revision.get() != request) return@post
            activeRevision = request
            try {
                native.clear(handle)
                sentDemo = null
                pendingAt = 0L
                lastStatus = 4
                postStatus(request, "${reason}消去を要求しました。完了通知はないため、Discordのプロフィールで確認してください。")
            } catch (_: Exception) { nativeFailure() }
            catch (_: LinkageError) { nativeFailure() }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (closed.get() || handle == 0L || !state.value.ready) return
            try {
                val fields = native.poll(handle)
                if (activeRevision == revision.get() && sentDemo != null && fields[0] != lastStatus) {
                    lastStatus = fields[0]
                    when (fields[0]) {
                        2 -> { pendingAt = 0L; postStatus(activeRevision, "SDKが更新成功を返しました。Discord側の曲名・表示タイプも確認してください。") }
                        3 -> {
                            native.clear(handle)
                            pendingAt = 0L; lastStatus = 4; sentDemo = null
                            postStatus(activeRevision, "更新に失敗しました (type=${fields[1]}, code=${fields[2]})。消去を要求しました。自動再送はしません。", stopDemo = true)
                        }
                    }
                }
                if (activeRevision == revision.get() && pendingAt != 0L && elapsed() - pendingAt >= 15_000L) {
                    native.clear(handle)
                    pendingAt = 0L
                    lastStatus = 4
                    sentDemo = null
                    postStatus(activeRevision, "15秒以内に結果が返りませんでした。消去を要求しました。自動再送はしません。", stopDemo = true)
                }
                if (activeRevision == revision.get() && sentDemo?.positionAt(elapsed()) == DemoPlayback.DURATION_MS && endPostedRevision != activeRevision) {
                    endPostedRevision = activeRevision
                    val ending = activeRevision
                    main.post { if (!closed.get() && revision.get() == ending) finishIfDue() }
                }
                worker?.postDelayed(this, 50)
            } catch (_: Exception) { nativeFailure() }
            catch (_: LinkageError) { nativeFailure() }
        }
    }

    private fun postStatus(request: Long, message: String, stopDemo: Boolean = false) {
        main.post {
            if (closed.get() || revision.get() != request) return@post
            mutableState.update { it.copy(busy = false, message = message, demo = if (stopDemo) null else it.demo) }
        }
    }

    private fun nativeFailure() {
        main.post {
            if (closed.get()) return@post
            revision.incrementAndGet()
            mutableState.update { it.copy(ready = false, busy = false, demo = null, message = "SDKの読み込み・操作に失敗しました。検証アプリを開き直し、Discord側の表示も確認してください。") }
            worker?.post {
                if (handle != 0L) {
                    try { native.clear(handle) } catch (_: Exception) { } catch (_: LinkageError) { }
                    pendingAt = 0L; sentDemo = null
                }
            }
        }
    }

    override fun onCleared() {
        closed.set(true)
        revision.incrementAndGet()
        main.removeCallbacksAndMessages(null)
        worker?.removeCallbacksAndMessages(null)
        val owner = thread
        worker?.post {
            if (handle != 0L) {
                try { native.clear(handle); native.poll(handle) } catch (_: Exception) { } catch (_: LinkageError) { }
                worker?.postDelayed({
                    try { native.poll(handle) } catch (_: Exception) { } catch (_: LinkageError) { }
                    try { native.destroy(handle) } catch (_: Exception) { } catch (_: LinkageError) { }
                    handle = 0L
                    owner?.quitSafely()
                }, 500)
            } else owner?.quitSafely()
        }
    }
}
