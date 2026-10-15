package com.kadaiyare.izuna.data.history
import com.kadaiyare.izuna.BuildConfig

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.kadaiyare.izuna.core.model.ListeningSession
import com.kadaiyare.izuna.core.model.ScrobbleDestination
import com.kadaiyare.izuna.data.audio.readFlacScrobbleTags
import com.kadaiyare.izuna.data.lastfm.LastFmAuthRepository
import com.kadaiyare.izuna.data.lastfm.ScrobbleScheduler
import com.kadaiyare.izuna.data.listenbrainz.ListenBrainzDestination
import com.kadaiyare.izuna.data.listenbrainz.ListenBrainzQueue
import com.kadaiyare.izuna.data.listenbrainz.ListenBrainzScheduler
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One DB and ordered writer per process. History failures never propagate to the Player. */
class HistoryRepository private constructor(
    context: Context,
    private val database: HistoryDatabase = Room.databaseBuilder(context, HistoryDatabase::class.java, "history.db").build(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val enqueueLastFm: () -> Unit = { ScrobbleScheduler.enqueue(context) },
    private val enqueueListenBrainz: () -> Unit = { ListenBrainzScheduler.enqueue(context) },
) {
    internal val dao = database.history()
    private val auth = LastFmAuthRepository.get(context)
    private val listenBrainz = ListenBrainzQueue.get(context)
    private sealed interface Command {
        data class Update(val session: ListeningSession, val generation: Long, val destination: ScrobbleDestination?,
                          val listenBrainz: ListenBrainzDestination?) : Command
        data class Clear(val completion: CompletableDeferred<Unit>) : Command
    }
    private val updates = Channel<Command>(Channel.UNLIMITED)
    private val mutableGeneration = MutableStateFlow(0L)
    internal val generation = mutableGeneration.asStateFlow()
    private val writer = ScrobbleHistoryWriter(dao, auth) { uri ->
        context.contentResolver.openInputStream(uri.toUri())?.use(::readFlacScrobbleTags)
    }
    private val initialized = CompletableDeferred<Unit>()
    private val mutableWarning = MutableStateFlow<String?>(null)
    val warning = mutableWarning.asStateFlow()

    init {
        scope.launch {
            try {
                dao.finishInterruptedSessions()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                storageFailure(error)
            } finally {
                initialized.complete(Unit)
            }
            for (command in updates) {
                if (command is Command.Clear) {
                    try {
                        dao.clearHistory()
                        mutableGeneration.value++
                        mutableWarning.value = null
                        command.completion.complete(Unit)
                    } catch (error: Exception) {
                        command.completion.completeExceptionally(error)
                        // A closed Room scope can cancel its own query while our writer is still active.
                        currentCoroutineContext().ensureActive()
                        Log.w("IzunaHistory", "Unable to clear listening history", if (BuildConfig.DEBUG) error else null)
                    }
                    continue
                }
                command as Command.Update
                // Updates captured before a successful clear cannot recreate erased sessions.
                if (command.generation != mutableGeneration.value) continue
                val session = command.session
                val destination = command.destination
                val compatibleDestination = command.listenBrainz
                try {
                    val queued = writer.save(session, destination)
                    mutableWarning.value = null
                    if (queued) try { enqueueLastFm() }
                    catch (_: Exception) { Log.w("IzunaScrobble", "Unable to schedule persisted scrobbles") }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    storageFailure(error)
                }
                try {
                    if (listenBrainz.writer.save(session, compatibleDestination)) enqueueListenBrainz()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.w("IzunaScrobble", "Unable to persist or schedule compatible scrobbles")
                }
            }
        }
        scope.launch {
            listenBrainz.auth.state.map { listenBrainz.auth.destination() }.distinctUntilChanged().collect { destination ->
                if (destination != null) try { enqueueListenBrainz() }
                catch (_: Exception) { /* Durable jobs are retained. */ }
            }
        }
        scope.launch {
            auth.state.map { auth.destination() }.distinctUntilChanged().collect { destination ->
                if (destination != null) {
                    initialized.await()
                    try { enqueueLastFm() } catch (_: Exception) { /* Persisted jobs remain recoverable. */ }
                }
            }
        }
    }

    fun save(session: ListeningSession, generation: Long = mutableGeneration.value) {
        updates.trySend(Command.Update(session, generation, auth.destination(), listenBrainz.auth.destination()))
    }
    suspend fun clearHistory() {
        val completion = CompletableDeferred<Unit>()
        updates.send(Command.Clear(completion))
        completion.await()
    }
    internal suspend fun awaitInitialized() { initialized.await() }
    fun observeJobCounts(account: String) = dao.observeJobCounts(account)
    fun observeRecent() = flow {
        initialized.await()
        emitAll(dao.observeRecent().map { rows -> rows.map(HistoryEntry::session) })
    }

    private fun storageFailure(error: Exception) {
        Log.w("IzunaHistory", "Unable to save listening history", if (BuildConfig.DEBUG) error else null)
        mutableWarning.value = "履歴を保存できません。端末の空き容量を確認してください。"
    }

    companion object {
        @Volatile private var instance: HistoryRepository? = null
        fun get(context: Context): HistoryRepository = instance ?: synchronized(this) {
            instance ?: HistoryRepository(context.applicationContext).also { instance = it }
        }
        internal fun create(context: Context, database: HistoryDatabase, scope: CoroutineScope) =
            HistoryRepository(context.applicationContext, database, scope, {}, {})
    }
}
