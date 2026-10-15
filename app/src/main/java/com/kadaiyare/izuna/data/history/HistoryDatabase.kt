package com.kadaiyare.izuna.data.history

import androidx.room.Dao
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kadaiyare.izuna.core.model.ListeningSession
import com.kadaiyare.izuna.core.model.ScrobbleDestination
import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import com.kadaiyare.izuna.core.model.Track
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "listening_sessions", indices = [Index(value = ["startedAtMs", "id"]), Index("track_uri")])
data class HistoryEntry(
    @PrimaryKey val id: String,
    @Embedded(prefix = "track_") val track: Track,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val endedAtMs: Long?,
    val listenedMs: Long,
    @ColumnInfo(defaultValue = "0") val qualifiedForScrobble: Boolean = false,
) {
    fun session() = ListeningSession(id, track, startedAtMs, updatedAtMs, endedAtMs, listenedMs)
}

fun ListeningSession.entry() = HistoryEntry(id, track, startedAtMs, updatedAtMs, endedAtMs, listenedMs, qualifiedForScrobble)

@Dao
interface HistoryDao {
    @Upsert suspend fun save(entry: HistoryEntry)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertJob(job: ScrobbleJob): Long
    @Query("SELECT * FROM scrobble_jobs WHERE provider = 'lastfm' AND accountName = :account AND status = 'pending' ORDER BY startedAtMs, id LIMIT :limit")
    suspend fun pendingJobs(account: String, limit: Int = 50): List<ScrobbleJob>

    @Query("UPDATE scrobble_jobs SET status = 'sending', attemptCount = attemptCount + 1 WHERE id = :id AND accountName = :account AND status = 'pending'")
    suspend fun claimJob(id: String, account: String): Int
    @Query("UPDATE scrobble_jobs SET status = :status, nextAttemptAtMs = :nextAt WHERE id = :id AND accountName = :account AND status = 'sending'")
    suspend fun finishJob(id: String, account: String, status: String, nextAt: Long = 0)
    @Query("UPDATE scrobble_jobs SET status = 'uncertain' WHERE accountName = :account AND provider = 'lastfm' AND status = 'sending'")
    suspend fun recoverSending(account: String)
    @Query("UPDATE scrobble_jobs SET status = 'pending', nextAttemptAtMs = :now WHERE accountName = :account AND provider = 'lastfm' AND status = 'uncertain'")
    suspend fun retryUncertain(account: String, now: Long)
    @Query("UPDATE scrobble_jobs SET nextAttemptAtMs = :now WHERE accountName = :account AND provider = 'lastfm' AND status = 'pending'")
    suspend fun retryPending(account: String, now: Long)
    @Query("SELECT status, COUNT(*) AS count FROM scrobble_jobs WHERE provider = 'lastfm' AND accountName = :account GROUP BY status")
    fun observeJobCounts(account: String): Flow<List<ScrobbleCount>>

    @Transaction
    suspend fun saveWithScrobble(entry: HistoryEntry, destination: ScrobbleDestination? = null, metadata: ScrobbleMetadata? = null): Boolean {
        save(entry)
        if (!entry.qualifiedForScrobble || destination == null || metadata?.valid() != true ||
            entry.startedAtMs < destination.enabledAtMs || entry.startedAtMs < 1_000) return false
        return insertJob(ScrobbleJob(java.util.UUID.randomUUID().toString(), entry.id, destination.accountName,
            metadata.title.trim(), metadata.artist.trim(), metadata.album?.trim()?.takeIf { it.isNotEmpty() },
            entry.track.durationMs, entry.startedAtMs, entry.updatedAtMs)) != -1L
    }
    @Query("SELECT * FROM listening_sessions ORDER BY startedAtMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<HistoryEntry>>
    @Query("UPDATE listening_sessions SET endedAtMs = updatedAtMs WHERE endedAtMs IS NULL")
    suspend fun finishInterruptedSessions()
    @Query("DELETE FROM listening_sessions")
    suspend fun clearHistory()
}

data class ScrobbleCount(val status: String, val count: Int)

class HistoryTimingMigration : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE listening_sessions SET qualifiedForScrobble = " +
            "CASE WHEN track_durationMs > 30000 AND listenedMs >= MIN(track_durationMs / 2 + track_durationMs % 2, 240000) THEN 1 ELSE 0 END")
    }
}

@Database(entities = [HistoryEntry::class, ScrobbleJob::class], version = 4, exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = HistoryTimingMigration::class), AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4)])
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
}
