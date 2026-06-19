package dev.dean.voice.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.dean.voice.data.db.entities.AppUsageRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface AppUsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: AppUsageRecord)

    /**
     * Returns accepted paste counts per app, for a given intent, in the last [windowMs] ms.
     * Used by the frequency ranking logic to sort the target app menu.
     */
    @Query("""
        SELECT targetAppId, targetAppName, COUNT(*) as useCount
        FROM app_usage
        WHERE intent = :intent
          AND userAccepted = 1
          AND timestamp > :since
        GROUP BY targetAppId
        ORDER BY useCount DESC
    """)
    suspend fun getRankedApps(intent: String, since: Long): List<RankedApp>

    @Query("DELETE FROM app_usage")
    suspend fun deleteAll()

    /** Projected result for ranked app query — not a Room entity, just a result class. */
    data class RankedApp(
        val targetAppId: String,
        val targetAppName: String,
        val useCount: Int,
    )
}
