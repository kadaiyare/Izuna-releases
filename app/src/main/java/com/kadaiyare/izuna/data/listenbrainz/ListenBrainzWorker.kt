package com.kadaiyare.izuna.data.listenbrainz

import android.content.Context
import androidx.work.*
import com.kadaiyare.izuna.data.lastfm.scrobbleRetryDelay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

internal enum class ListenBrainzDrain { Done, Retry, More }
internal class ListenBrainzDelivery(private val dao: ListenBrainzDao, private val auth: ListenBrainzRepository,
    private val client: ListenBrainzClient, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun drain(): ListenBrainzDrain {
        repeat(10) {
            val result = auth.withEnabled { connection ->
                dao.recover(connection.id)
                val job = dao.next(connection.id) ?: return@withEnabled ListenBrainzDrain.Done
                if (job.nextAttemptAtMs > now() || dao.claim(job.id, connection.id) == 0) return@withEnabled ListenBrainzDrain.Retry
                when (val response = client.submit(connection, job.metadata(), job.durationMs, job.startedAtMs)) {
                    ListenBrainzResult.Accepted -> dao.finish(job.id, connection.id, "accepted")
                    ListenBrainzResult.Rejected -> dao.finish(job.id, connection.id, "rejected")
                    ListenBrainzResult.Uncertain -> dao.finish(job.id, connection.id, "uncertain")
                    ListenBrainzResult.Unauthorized -> {
                        dao.finish(job.id, connection.id, "pending")
                        auth.invalidate()
                        return@withEnabled ListenBrainzDrain.Done
                    }
                    is ListenBrainzResult.Retry -> {
                        val delayMs = maxOf(scrobbleRetryDelay(job.attempts), response.afterSeconds.coerceIn(1, 86_400) * 1000)
                        dao.finish(job.id, connection.id, "pending", now() + delayMs)
                        return@withEnabled ListenBrainzDrain.Retry
                    }
                }
                ListenBrainzDrain.More
            } ?: ListenBrainzDrain.Done
            if (result != ListenBrainzDrain.More) return result
        }
        return ListenBrainzDrain.More
    }
}

class ListenBrainzWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val queue = ListenBrainzQueue.get(applicationContext)
        queue.auth.state.first { !it.loading }
        when (ListenBrainzDelivery(queue.dao, queue.auth, ListenBrainzClient()).drain()) {
            ListenBrainzDrain.Done -> Result.success()
            ListenBrainzDrain.Retry -> Result.retry()
            ListenBrainzDrain.More -> { ListenBrainzScheduler.enqueue(applicationContext); Result.success() }
        }
    } catch (failure: CancellationException) { throw failure }
    catch (_: Exception) { Result.retry() }
}

internal object ListenBrainzScheduler {
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<ListenBrainzWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork("izuna.listenbrainz.delivery", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
