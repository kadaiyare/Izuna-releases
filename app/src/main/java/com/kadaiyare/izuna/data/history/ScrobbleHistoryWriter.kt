package com.kadaiyare.izuna.data.history

import com.kadaiyare.izuna.core.model.ListeningSession
import com.kadaiyare.izuna.core.model.ScrobbleDestination
import com.kadaiyare.izuna.core.model.ScrobbleMetadata
import com.kadaiyare.izuna.data.lastfm.LastFmAuthRepository
import kotlinx.coroutines.CancellationException

/** Used by the ordered history writer; file access stays away from the Player thread. */
internal class ScrobbleHistoryWriter(
    private val dao: HistoryDao, private val auth: LastFmAuthRepository,
    private val readTags: suspend (String) -> ScrobbleMetadata?,
) {
    private val tags = LinkedHashMap<String, ScrobbleMetadata?>()

    suspend fun save(session: ListeningSession, destination: ScrobbleDestination?): Boolean {
        if (destination == null || !session.qualifiedForScrobble || session.startedAtMs < destination.enabledAtMs || auth.destination() != destination) {
            dao.save(session.entry())
            return false
        }
        if (!tags.containsKey(session.id)) {
            tags[session.id] = try { readTags(session.track.uri) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { null }
            if (tags.size > 16) tags.remove(tags.keys.first())
        }
        val queued = auth.withDestination(destination) {
            dao.saveWithScrobble(session.entry(), destination, tags[session.id])
        }
        if (queued == null) dao.save(session.entry())
        return queued == true
    }
}
