package dev.dean.voice.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A completed voice-to-text session: raw Whisper output + LLM-cleaned result.
 * Stored for debugging and in-context learning pair extraction.
 */
@Entity(tableName = "transcriptions")
data class Transcription(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val rawText: String,
    val cleanedText: String,
    val intent: String,               // VoiceIntent.name
    val targetApp: String? = null,    // Package ID of the app the text was sent to
    val timestamp: Long = System.currentTimeMillis(),
    val audioLengthMs: Int,
    val processingTimeMs: Int,
)
