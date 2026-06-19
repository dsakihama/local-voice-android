package dev.dean.voice.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A raw→cleaned transcription pair used for in-context learning.
 *
 * Each inference call pulls the most recent N pairs for the current intent
 * and injects them as few-shot examples into the LLM prompt. This adapts
 * the cleanup style to the user's personal voice without any fine-tuning.
 *
 * Phase 3 will add the retrieval + injection logic.
 */
@Entity(tableName = "training_pairs")
data class TrainingPair(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val rawText: String,
    val cleanedText: String,
    val intent: String,               // VoiceIntent.name
    val timestamp: Long = System.currentTimeMillis(),
    val userRating: Float? = null,    // 0–5 stars, optional (Phase 3+)
)
