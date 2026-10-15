package com.kadaiyare.izuna.data.lastfm

import com.kadaiyare.izuna.data.history.HistoryDao

internal fun scrobbleRetryDelay(attempt: Int): Long = minOf(21_600_000L, 60_000L * (1L shl attempt.coerceIn(0, 9)))
internal enum class ScrobbleDrain { Done, Retry, More }

/** A durable sending marker makes interrupted/ambiguous writes require explicit retry. */
internal class ScrobbleDelivery(
    private val dao: HistoryDao, private val auth: LastFmAuthRepository, private val api: ScrobbleApi,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private enum class Step { Done, More, Retry }

    suspend fun drain(): ScrobbleDrain {
        repeat(10) {
            // Release the auth lock between requests so disable/disconnect can finish promptly.
            val step = auth.withEnabledSession { session ->
                dao.recoverSending(session.accountName)
                val job = dao.pendingJobs(session.accountName, 1).firstOrNull() ?: return@withEnabledSession Step.Done
                if (job.nextAttemptAtMs > now()) return@withEnabledSession Step.Retry
                if (dao.claimJob(job.id, session.accountName) == 0) return@withEnabledSession Step.Retry
                val result = api.submit(job, session)
                when (result) {
                    ScrobbleResult.Accepted -> dao.finishJob(job.id, session.accountName, "accepted")
                    is ScrobbleResult.Ignored -> dao.finishJob(job.id, session.accountName, "ignored:${result.code}")
                    is ScrobbleResult.Rejected -> dao.finishJob(job.id, session.accountName, "rejected:${result.code}")
                    ScrobbleResult.Uncertain -> dao.finishJob(job.id, session.accountName, "uncertain")
                    ScrobbleResult.Reauthenticate -> {
                        dao.finishJob(job.id, session.accountName, "pending", now())
                        throw LastFmInvalidSession()
                    }
                    ScrobbleResult.Retry -> {
                        dao.finishJob(job.id, session.accountName, "pending", now() + scrobbleRetryDelay(job.attemptCount))
                        return@withEnabledSession Step.Retry
                    }
                }
                Step.More
            } ?: Step.Done
            when (step) {
                Step.Done -> return ScrobbleDrain.Done
                Step.Retry -> return ScrobbleDrain.Retry
                Step.More -> Unit
            }
        }
        return auth.withEnabledSession { session ->
            if (dao.pendingJobs(session.accountName, 1).isEmpty()) ScrobbleDrain.Done else ScrobbleDrain.More
        } ?: ScrobbleDrain.Done
    }
}
