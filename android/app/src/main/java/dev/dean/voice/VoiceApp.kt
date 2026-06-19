package dev.dean.voice

import android.app.Application
import dev.dean.voice.data.db.VoiceDatabase
import dev.dean.voice.model.ModelManager

/**
 * Application class. Holds singleton references to the database and model manager.
 *
 * Models are loaded lazily on first access (triggered by the first inference request).
 * Both Whisper (~394 MB) and Phi-2 (~2.18 GB) live in RAM simultaneously when active.
 */
class VoiceApp : Application() {

    /** Room database — single instance for the app lifetime. */
    val database: VoiceDatabase by lazy {
        VoiceDatabase.getInstance(this)
    }

    /** Model manager — loads ORT sessions for Whisper + Phi-2 on first inference. */
    val modelManager: ModelManager by lazy {
        ModelManager(this)
    }

    override fun onCreate() {
        super.onCreate()
    }
}
