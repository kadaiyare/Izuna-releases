package com.kadaiyare.izuna.data.listenbrainz

import android.content.Context
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ListenBrainzState(val loading: Boolean = true, val busy: Boolean = false, val root: String? = null,
    val account: String? = null, val destinationId: String? = null, val enabledAtMs: Long = 0,
    val storageProblem: Boolean = false, val message: String? = null)
internal data class ListenBrainzDestination(val id: String, val enabledAtMs: Long)

internal class ListenBrainzRepository(
    private val store: ListenBrainzStore, private val client: ListenBrainzClient,
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var connection: ListenBrainzConnection? = null
    private val mutableState = MutableStateFlow(ListenBrainzState())
    val state = mutableState.asStateFlow()
    init { scope.launch { lock.withLock {
        try { connection = store.read(); publish() }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { mutableState.value = ListenBrainzState(loading = false, storageProblem = true,
            message = "接続情報を読み込めません。接続情報をリセットしてください。") }
    } } }

    suspend fun connect(input: String, token: String) = operation {
        val root = try { listenBrainzRoot(input) } catch (_: Exception) {
            publish("HTTPSのAPI URLを入力してください。末尾は /1 まで指定できます。"); return@operation
        }
        if (!validListenBrainzToken(token.trim())) { publish("有効なユーザートークンを入力してください。"); return@operation }
        val account = client.validate(root, token.trim())
        store.write(ListenBrainzConnection(root, token.trim(), account))
        connection = ListenBrainzConnection(root, token.trim(), account)
        publish("接続しました。送信はまだオフです。")
    }
    suspend fun disconnect() = operation { store.write(null); connection = null; publish() }
    suspend fun setEnabled(enabled: Boolean) = operation {
        val current = connection ?: return@operation
        val next = current.enabled(if (enabled) now().coerceAtLeast(1) else 0)
        store.write(next); connection = next; publish()
    }
    fun destination(): ListenBrainzDestination? = state.value.let {
        if (it.loading || it.storageProblem || it.enabledAtMs <= 0 || it.destinationId == null) null
        else ListenBrainzDestination(it.destinationId, it.enabledAtMs)
    }
    suspend fun <T> withDestination(expected: ListenBrainzDestination, action: suspend () -> T): T? = lock.withLock {
        if (destination() != expected) null else action()
    }
    suspend fun <T> withEnabled(action: suspend (ListenBrainzConnection) -> T): T? = lock.withLock {
        val current = connection ?: return@withLock null
        if (destination() == null) return@withLock null
        action(current)
    }
    // Called under withEnabled's lock on an authentication failure. Stop even if disk writes fail.
    fun invalidate() {
        connection = null
        try { store.write(null); publish("トークンが無効です。接続し直してください。") }
        catch (_: Exception) { mutableState.value = state.value.copy(enabledAtMs = 0, storageProblem = true,
            message = "認証が無効です。接続情報をリセットしてください。") }
    }
    private fun publish(message: String? = null) {
        mutableState.value = ListenBrainzState(loading = false, busy = state.value.busy,
            root = connection?.root, account = connection?.account, destinationId = connection?.id,
            enabledAtMs = connection?.enabledAtMs ?: 0, message = message)
    }
    private suspend fun operation(action: suspend () -> Unit) = withContext(Dispatchers.IO) { lock.withLock {
        mutableState.value = state.value.copy(busy = true, message = null)
        try { action() }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { mutableState.value = state.value.copy(message = "操作を完了できませんでした。URL・トークン・通信環境を確認してください。") }
        finally { mutableState.value = state.value.copy(busy = false) }
    } }
    companion object {
        @Volatile private var instance: ListenBrainzRepository? = null
        fun get(context: Context): ListenBrainzRepository = instance ?: synchronized(this) {
            instance ?: ListenBrainzRepository(EncryptedListenBrainzStore(File(context.applicationContext.noBackupFilesDir,
                "listenbrainz.auth")), ListenBrainzClient()).also { instance = it }
        }
    }
}
