package dev.dean.voice.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.dean.voice.data.db.dao.AppUsageDao
import dev.dean.voice.data.db.dao.TrainingPairDao
import dev.dean.voice.data.db.dao.TranscriptionDao
import dev.dean.voice.data.db.entities.AppUsageRecord
import dev.dean.voice.data.db.entities.TrainingPair
import dev.dean.voice.data.db.entities.Transcription

@Database(
    entities = [
        Transcription::class,
        AppUsageRecord::class,
        TrainingPair::class,
    ],
    version = 1,
    exportSchema = true,   // Schema exported to app/schemas/ for migration tracking
)
abstract class VoiceDatabase : RoomDatabase() {

    abstract fun transcriptionDao(): TranscriptionDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun trainingPairDao(): TrainingPairDao

    companion object {
        private const val DB_NAME = "voice_database"

        @Volatile
        private var INSTANCE: VoiceDatabase? = null

        fun getInstance(context: Context): VoiceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VoiceDatabase::class.java,
                    DB_NAME,
                )
                    .fallbackToDestructiveMigration()  // TODO: replace with real migrations before v1 release
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
