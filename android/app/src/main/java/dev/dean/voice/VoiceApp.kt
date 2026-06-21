package dev.dean.voice

import android.app.Application
import dev.dean.voice.data.db.VoiceDatabase
import dev.dean.voice.data.db.VoiceRepository

class VoiceApp : Application() {

    val database: VoiceDatabase by lazy { VoiceDatabase.getInstance(this) }
    val repository: VoiceRepository by lazy { VoiceRepository(database) }

    override fun onCreate() {
        super.onCreate()
    }
}
