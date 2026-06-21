package dev.dean.voice.data.db

import dev.dean.voice.data.db.entities.AppUsageRecord
import dev.dean.voice.data.db.entities.TrainingPair
import dev.dean.voice.data.db.entities.Transcription

class VoiceRepository(db: VoiceDatabase) {

    private val transcriptions = db.transcriptionDao()
    private val appUsage = db.appUsageDao()
    private val trainingPairs = db.trainingPairDao()

    suspend fun saveTranscription(record: Transcription) = transcriptions.insert(record)
    fun recentTranscriptions(limit: Int = 50) = transcriptions.getRecent(limit)

    suspend fun saveAppUsage(record: AppUsageRecord) = appUsage.insert(record)
    suspend fun getRankedApps(intent: String, since: Long) = appUsage.getRankedApps(intent, since)

    suspend fun saveTrainingPair(pair: TrainingPair) = trainingPairs.insert(pair)
    suspend fun getTrainingPairs(intent: String, limit: Int = 10) = trainingPairs.getRecent(intent, limit)
}
