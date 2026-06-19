package dev.dean.voice.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.dean.voice.data.db.entities.TrainingPair

@Dao
interface TrainingPairDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pair: TrainingPair)

    /**
     * Fetch the N most recent training pairs for a given intent.
     * Injected as few-shot examples into the LLM cleanup prompt.
     */
    @Query("""
        SELECT * FROM training_pairs
        WHERE intent = :intent
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getRecent(intent: String, limit: Int = 10): List<TrainingPair>

    @Query("SELECT COUNT(*) FROM training_pairs WHERE intent = :intent")
    suspend fun countForIntent(intent: String): Int

    @Query("DELETE FROM training_pairs")
    suspend fun deleteAll()
}
