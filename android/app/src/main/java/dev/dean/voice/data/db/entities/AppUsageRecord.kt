package dev.dean.voice.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Tracks which target app was selected for each intent session.
 * Used to build the frequency-ranked app menu (last 30 days, recency-weighted).
 *
 * Ranking logic (Phase 5):
 *   - Last 7 days: weight = 1.0
 *   - 8–30 days:   weight = linear decay to 0.3
 *   - >30 days:    excluded
 */
@Entity(tableName = "app_usage")
data class AppUsageRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val targetAppId: String,          // Package name, e.g. "com.slack"
    val targetAppName: String,        // Display name, e.g. "Slack"
    val intent: String,               // VoiceIntent.name
    val timestamp: Long = System.currentTimeMillis(),
    val userAccepted: Boolean,        // true = user pasted; false = dismissed
)
