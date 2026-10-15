package com.kadaiyare.izuna.data.listenbrainz

import android.content.Context
import androidx.core.net.toUri
import androidx.room.*
import com.kadaiyare.izuna.core.model.ListeningSession
import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import com.kadaiyare.izuna.data.audio.readFlacScrobbleTags
import com.kadaiyare.izuna.data.history.ScrobbleCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "listenbrainz_jobs", indices = [Index(value = ["sessionId", "destinationId"], unique = true),
    Index(value = ["destinationId", "status", "startedAtMs"])])
internal data class ListenBrainzJob(@PrimaryKey val id: String, val sessionId: String, val destinationId: String,
    val title: String, val artist: String, val album: String?, val durationMs: Long, val startedAtMs: Long,
    val status: String = "pending", val attempts: Int = 0, val nextAttemptAtMs: Long = 0) {
    fun metadata() = ScrobbleMetadata(title, artist, album)
}

@Dao
internal interface ListenBrainzDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(job: ListenBrainzJob): Long
    @Query("SELECT * FROM listenbrainz_jobs WHERE destinationId = :destination AND status = 'pending' ORDER BY startedAtMs, id LIMIT 1")
    suspend fun next(destination: String): ListenBrainzJob?
    @Query("UPDATE listenbrainz_jobs SET status = 'sending', attempts = attempts + 1 WHERE id = :id AND destinationId = :destination AND status = 'pending'")
    suspend fun claim(id: String, destination: String): Int
    @Query("UPDATE listenbrainz_jobs SET status = :status, nextAttemptAtMs = :nextAt WHERE id = :id AND destinationId = :destination AND status = 'sending'")
    suspend fun finish(id: String, destination: String, status: String, nextAt: Long = 0)
    @Query("UPDATE listenbrainz_jobs SET status = 'uncertain' WHERE destinationId = :destination AND status = 'sending'")
    suspend fun recover(destination: String)
    @Query("UPDATE listenbrainz_jobs SET status = 'pending', nextAttemptAtMs = :now WHERE destinationId = :destination AND status = :status")
    suspend fun retry(destination: String, status: String, now: Long)
    @Query("SELECT status, COUNT(*) AS count FROM listenbrainz_jobs WHERE destinationId = :destination GROUP BY status")
    fun counts(destination: String): Flow<List<ScrobbleCount>>
}

@Database(entities = [ListenBrainzJob::class], version = 1, exportSchema = true)
internal abstract class ListenBrainzDatabase : RoomDatabase() { abstract fun jobs(): ListenBrainzDao }

internal class ListenBrainzHistoryWriter(private val dao: ListenBrainzDao, private val auth: ListenBrainzRepository,
    private val readTags: suspend (String) -> ScrobbleMetadata?) {
    private val tags = LinkedHashMap<String, ScrobbleMetadata?>()
    suspend fun save(session: ListeningSession, destination: ListenBrainzDestination?): Boolean {
        if (destination == null || !listenBrainzQualifies(session.track.durationMs, session.listenedMs) || session.startedAtMs < destination.enabledAtMs ||
            session.startedAtMs < 1000 || auth.destination() != destination) return false
        if (!tags.containsKey(session.id)) {
            tags[session.id] = try { readTags(session.track.uri) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { null }
            if (tags.size > 16) tags.remove(tags.keys.first())
        }
        val metadata = tags[session.id]?.takeIf { it.valid() } ?: return false
        return auth.withDestination(destination) {
            dao.insert(ListenBrainzJob(UUID.randomUUID().toString(), session.id, destination.id,
                metadata.title.trim(), metadata.artist.trim(), metadata.album?.trim()?.takeIf { it.isNotEmpty() },
                session.track.durationMs, session.startedAtMs)) != -1L
        } == true
    }
}

internal class ListenBrainzQueue private constructor(context: Context) {
    private val database = Room.databaseBuilder(context, ListenBrainzDatabase::class.java, "listenbrainz.db").build()
    val dao = database.jobs()
    val auth = ListenBrainzRepository.get(context)
    val writer = ListenBrainzHistoryWriter(dao, auth) { uri ->
        context.contentResolver.openInputStream(uri.toUri())?.use(::readFlacScrobbleTags)
    }
    companion object {
        @Volatile private var instance: ListenBrainzQueue? = null
        fun get(context: Context): ListenBrainzQueue = instance ?: synchronized(this) {
            instance ?: ListenBrainzQueue(context.applicationContext).also { instance = it }
        }
    }
}
