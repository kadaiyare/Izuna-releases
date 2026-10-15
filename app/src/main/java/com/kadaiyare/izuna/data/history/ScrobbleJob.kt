package com.kadaiyare.izuna.data.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An immutable tag snapshot bound to the account that authorized it. No credentials are stored. */
@Entity(tableName = "scrobble_jobs", indices = [
    Index(value = ["sessionId", "provider", "accountName"], unique = true),
    Index(value = ["provider", "accountName", "status", "startedAtMs"]),
])
data class ScrobbleJob(
    @PrimaryKey val id: String,
    val sessionId: String,
    val accountName: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val startedAtMs: Long,
    val queuedAtMs: Long,
    val status: String = "pending",
    val attemptCount: Int = 0,
    val nextAttemptAtMs: Long = queuedAtMs,
    val provider: String = "lastfm",
)
