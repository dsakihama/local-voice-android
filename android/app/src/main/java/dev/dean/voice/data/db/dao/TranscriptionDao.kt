package dev.dean.voice.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.dean.voice.data.db.entities.Transcription
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transcription: Transcription)

    @Query("SELECT * FROM transcriptions ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<Transcription>>

    @Query("SELECT COUNT(*) FROM transcriptions")
    suspend fun count(): Int

    @Query("DELETE FROM transcriptions")
    suspend fun deleteAll()
}
