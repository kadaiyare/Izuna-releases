package com.kadaiyare.izuna.data.lastfm

import android.content.Context
import com.kadaiyare.izuna.BuildConfig
import com.kadaiyare.izuna.core.model.ScrobbleDestination
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LastFmAuthState(
    val configured: Boolean,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val accountName: String? = null,
    val pending: Boolean = false,
    val storageProblem: Boolean = false,
    val message: String? = null,
    val enabledAtMs: Long = 0,
)

class LastFmAuthRepository internal constructor(
    private val config: LastFmConfig,
    private val api: LastFmAuthApi,
    private val store: LastFmAuthStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Independent of the Activity: returning from the browser or rotation cannot abandon a consumed token.
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var auth: StoredLastFmAuth = StoredLastFmAuth.Empty
    private val mutableState = MutableStateFlow(LastFmAuthState(config.configured))
    val state = mutableState.asStateFlow()

    init { scope.launch { load() } }

    private suspend fun load() = lock.withLock {
        try {
            auth = store.read()
            val valid = when (val saved = auth) {
                StoredLastFmAuth.Empty -> true
                is StoredLastFmAuth.Pending -> saved.apiKey == config.apiKey && saved.validAt(now())
                is StoredLastFmAuth.Connected -> saved.apiKey == config.apiKey
            }
            if (!valid) save(StoredLastFmAuth.Empty)
            publish()
        } catch (_: Exception) {
            mutableState.value = LastFmAuthState(config.configured, loading = false, storageProblem = true,
                message = "保存した接続情報を読み込めません。接続情報をリセットして、もう一度接続してください。")
        }
    }

    internal suspend fun begin(): String? = operation {
        if (!config.configured || auth is StoredLastFmAuth.Connected || state.value.storageProblem) return@operation null
        var pending = auth as? StoredLastFmAuth.Pending
        if (pending == null || !pending.validAt(now())) {
            pending = StoredLastFmAuth.Pending(api.token(), now(), config.apiKey)
            save(pending)
        }
        publish()
        config.authorizationUrl(pending.token)
    }

    internal suspend fun confirm() = operation {
        if (!config.configured || state.value.storageProblem) return@operation
        val pending = auth as? StoredLastFmAuth.Pending ?: return@operation
        if (!pending.validAt(now())) {
            save(StoredLastFmAuth.Empty)
            publish("認証の有効期限が切れました。もう一度接続してください。")
            return@operation
        }
        try {
            val connected = api.session(pending.token)
            try {
                save(connected)
            } catch (failure: Exception) {
                // Last.fm has consumed the token; never offer to exchange it again.
                auth = StoredLastFmAuth.Empty
                mutableState.value = state.value.copy(accountName = null, pending = false, storageProblem = true,
                    message = "接続情報を保存できませんでした。接続情報をリセットして、もう一度接続してください。")
                return@operation
            }
            publish()
        } catch (failure: LastFmApiException) {
            if (failure.code == 4 || failure.code == 15) save(StoredLastFmAuth.Empty)
            throw failure
        }
    }

    internal suspend fun disconnect() = operation {
        save(StoredLastFmAuth.Empty)
        publish("端末の接続情報を削除しました。Last.fm側の許可はLast.fmの設定から取り消せます。")
    }

    internal suspend fun setScrobbling(enabled: Boolean) = operation {
        val connected = auth as? StoredLastFmAuth.Connected ?: return@operation
        if (!config.configured || state.value.storageProblem) return@operation
        val timestamp = if (enabled) connected.enabledAtMs.takeIf { it > 0 } ?: now().coerceAtLeast(1) else 0
        save(StoredLastFmAuth.Connected(connected.accountName, connected.sessionKey, connected.apiKey, timestamp))
        publish()
    }

    fun destination(): ScrobbleDestination? = state.value.let {
        if (it.loading || it.storageProblem || !it.configured || it.enabledAtMs <= 0 || it.accountName == null) null
        else ScrobbleDestination(it.accountName, it.enabledAtMs)
    }

    internal suspend fun <T> withDestination(expected: ScrobbleDestination, action: suspend () -> T): T? = lock.withLock {
        if (destination() != expected) null else action()
    }

    internal suspend fun <T> withEnabledSession(action: suspend (StoredLastFmAuth.Connected) -> T): T? = lock.withLock {
        val connected = auth as? StoredLastFmAuth.Connected
        if (connected == null || destination() == null) return@withLock null
        try { action(connected) }
        catch (_: LastFmInvalidSession) {
            try {
                save(StoredLastFmAuth.Empty)
                publish("Last.fmの接続が無効になりました。再接続して送信を有効にしてください。")
            } catch (_: Exception) {
                auth = StoredLastFmAuth.Empty
                mutableState.value = LastFmAuthState(config.configured, loading = false, storageProblem = true,
                    message = "接続が無効になりました。接続情報をリセットしてください。")
            }
            null
        }
    }

    fun browserUnavailable() {
        mutableState.value = state.value.copy(message = "ブラウザを開けません。ブラウザを利用できる状態で再度開いてください。")
    }

    private fun save(value: StoredLastFmAuth) {
        try {
            store.write(value)
        } catch (failure: Exception) {
            throw AuthStorageException(failure)
        }
        auth = value
    }

    private fun publish(message: String? = null) {
        mutableState.value = LastFmAuthState(config.configured, loading = false, busy = state.value.busy,
            accountName = (auth as? StoredLastFmAuth.Connected)?.accountName,
            pending = auth is StoredLastFmAuth.Pending, message = message)
            .copy(enabledAtMs = (auth as? StoredLastFmAuth.Connected)?.enabledAtMs ?: 0)
    }

    private suspend fun <T> operation(action: suspend () -> T): T? = withContext(Dispatchers.IO) {
        lock.withLock {
            mutableState.value = state.value.copy(busy = true, message = null)
            try {
                action()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                val message = if (failure is AuthStorageException) "接続情報を保存・削除できませんでした。もう一度お試しください。" else when ((failure as? LastFmApiException)?.code) {
                    14 -> "まだ許可されていません。ブラウザで許可してから確認してください。"
                    4, 15 -> "認証が無効になりました。もう一度接続してください。"
                    10, 13, 26 -> "Last.fmの接続設定を確認してください。"
                    else -> "接続を完了できませんでした。通信環境を確認して、もう一度お試しください。"
                }
                mutableState.value = state.value.copy(message = message, pending = auth is StoredLastFmAuth.Pending)
                null
            } finally {
                mutableState.value = state.value.copy(busy = false)
            }
        }
    }

    companion object {
        @Volatile private var instance: LastFmAuthRepository? = null
        fun get(context: Context): LastFmAuthRepository = instance ?: synchronized(this) {
            instance ?: run {
                val config = LastFmConfig(BuildConfig.LAST_FM_API_KEY, BuildConfig.LAST_FM_SHARED_SECRET)
                LastFmAuthRepository(config, LastFmAuthClient(config),
                    EncryptedLastFmAuthStore(File(context.applicationContext.noBackupFilesDir, "lastfm.auth"), "izuna.lastfm.auth.v1"))
                    .also { instance = it }
            }
        }
    }
}

private class AuthStorageException(cause: Exception) : Exception("Authentication storage failed", cause)
internal class LastFmInvalidSession : Exception("Last.fm session invalid")
