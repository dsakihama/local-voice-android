package dev.dean.voice.model

import android.content.Context
import java.io.File

/**
 * Manages on-device ONNX model files and ORT session lifecycle.
 *
 * Models are NOT bundled in the APK (too large). They live in app-specific
 * external storage: /sdcard/Android/data/dev.dean.voice/files/models/
 * and are pushed via ADB — see scripts/push_models.sh.
 *
 * Phase 2: wire WhisperInference.
 * Phase 3: wire PhiInference.
 */
class ModelManager(private val context: Context) {

    // ── Model file paths ────────────────────────────────────────────

    /** Root directory for all model files on-device. */
    val modelDir: File
        get() = File(context.filesDir, "models")

    val whisperEncoderFile: File
        get() = File(modelDir, "whisper_small_int8/encoder_model_int8.onnx")

    val whisperDecoderFile: File
        get() = File(modelDir, "whisper_small_int8/decoder_model_int8.onnx")

    val phi2OnnxFile: File
        get() = File(modelDir, "phi2_int4/phi2_int4.onnx")

    val phi2DataFile: File
        get() = File(modelDir, "phi2_int4/phi2_int4.onnx.data")

    // ── Status ──────────────────────────────────────────────────────

    data class ModelStatus(
        val whisperReady: Boolean,
        val phiReady: Boolean,
        val missingFiles: List<String>,
    ) {
        val allReady: Boolean get() = whisperReady && phiReady
    }

    fun checkModelFiles(): ModelStatus {
        val missing = mutableListOf<String>()
        if (!whisperEncoderFile.exists()) missing += whisperEncoderFile.path
        if (!whisperDecoderFile.exists()) missing += whisperDecoderFile.path
        if (!phi2OnnxFile.exists())       missing += phi2OnnxFile.path
        if (!phi2DataFile.exists())       missing += phi2DataFile.path

        return ModelStatus(
            whisperReady = whisperEncoderFile.exists() && whisperDecoderFile.exists(),
            phiReady     = phi2OnnxFile.exists() && phi2DataFile.exists(),
            missingFiles = missing,
        )
    }

    // ── Inference ───────────────────────────────────────────────────

    val whisper: WhisperInference by lazy {
        WhisperInference(context, whisperEncoderFile, whisperDecoderFile)
    }

    fun loadWhisper() = whisper.load()

    fun closeWhisper() = whisper.close()

    /** TODO Phase 3: Initialize ONNX Runtime session for Phi-2. */
    fun loadPhi2() {
        check(phi2OnnxFile.exists()) {
            "Phi-2 model not found: ${phi2OnnxFile.path}\nRun scripts/push_models.sh"
        }
        // ORT session init goes here — note: phi2_int4.onnx.data must be co-located
    }
}
