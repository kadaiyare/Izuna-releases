package com.kadaiyare.izuna.data.lastfm

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kadaiyare.izuna.BuildConfig
import com.kadaiyare.izuna.data.history.HistoryRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class ScrobbleWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val auth = LastFmAuthRepository.get(applicationContext)
            auth.state.first { !it.loading }
            if (auth.destination() == null) return Result.success()
            val history = HistoryRepository.get(applicationContext)
            history.awaitInitialized()
            val client = ScrobbleClient(LastFmConfig(BuildConfig.LAST_FM_API_KEY, BuildConfig.LAST_FM_SHARED_SECRET))
            when (ScrobbleDelivery(history.dao, auth, client).drain()) {
                ScrobbleDrain.Done -> Result.success()
                ScrobbleDrain.Retry -> Result.retry()
                ScrobbleDrain.More -> { ScrobbleScheduler.enqueue(applicationContext); Result.success() }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { Result.retry() }
    }
}

internal object ScrobbleScheduler {
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScrobbleWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        // Appending avoids losing a new job queued just as a running worker finishes.
        WorkManager.getInstance(context).enqueueUniqueWork("izuna.lastfm.delivery", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
