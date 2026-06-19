package dev.dean.voice.model

import android.content.Context
import java.io.File

// Superseded by ML Kit GenAI Speech Recognition (Advanced mode) — see SttProbe.kt.
// ONNX Runtime dep removed in Phase 2.1 pivot. Implementation archived in python/.
// Stub retained so ModelManager compiles without modification during Phase 2.1 prototype.
class WhisperInference(
    private val context: Context,
    private val encoderFile: File,
    private val decoderFile: File,
) {
    fun isLoaded(): Boolean = false

    fun load() {
        throw UnsupportedOperationException("WhisperInference superseded by ML Kit GenAI STT.")
    }

    fun transcribe(pcm: ShortArray): String {
        throw UnsupportedOperationException("WhisperInference superseded by ML Kit GenAI STT.")
    }

    fun close() { /* no-op */ }
}
