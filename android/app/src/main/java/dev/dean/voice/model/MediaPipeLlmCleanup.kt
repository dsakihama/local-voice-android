package dev.dean.voice.model

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dev.dean.voice.intent.CleanupPrompts
import dev.dean.voice.intent.VoiceIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device text cleanup using MediaPipe LLM Inference + Gemma 3 1B (GGUF).
 *
 * Phase 2.2 — replaces GeminiNanoCleanup (ML Kit GenAI Prompt API) which hung
 * indefinitely on generateContent() in the AICore build on the Pixel 10 Pro XL.
 *
 * Model setup (one-time, dev):
 *   adb push gemma-3-1b-it-Q4_K_M.gguf /data/data/dev.dean.voice/files/gemma-3-1b-it-Q4_K_M.gguf
 *
 * Recommended GGUF: gemma-3-1b-it-q4_k_m.gguf (Hugging Face / Google)
 */
class MediaPipeLlmCleanup(private val context: Context) {

    companion object {
        private const val TAG = "MediaPipeLlmCleanup"
        private const val MODEL_FILENAME = "gemma3-1B-it-int4.task"
        private const val MAX_TOKENS = 512
    }

    sealed interface Result {
        data class Success(
            val rawText: String,
            val cleanedText: String,
            val latencyMs: Long,
        ) : Result
        data class Error(val message: String) : Result
    }

    private val modelFile get() = File(context.filesDir, MODEL_FILENAME)

    @Volatile private var llm: LlmInference? = null

    fun isModelReady(): Boolean = llm != null || modelFile.exists()

    /**
     * Loads model weights into memory so the first [clean] call doesn't block.
     * Safe to call multiple times — no-ops after the first successful load.
     * Call once after the UI is shown, before the user starts recording.
     */
    suspend fun warmup() {
        if (llm != null) return
        if (!modelFile.exists()) {
            Log.w(TAG, "Model not found at ${modelFile.absolutePath} — skipping warmup")
            return
        }
        Log.i(TAG, "Loading Gemma 3 1B from ${modelFile.absolutePath}…")
        try {
            withContext(Dispatchers.Default) {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
                llm = LlmInference.createFromOptions(context, options)
            }
            Log.i(TAG, "Gemma 3 1B ready.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed: ${e.message}", e)
        }
    }

    /**
     * Cleans [rawText] using Gemma 3 1B with the prompt template for [intent].
     * Initializes the model on first call if [warmup] was not called beforehand.
     */
    suspend fun clean(
        intent: VoiceIntent,
        rawText: String,
        examples: List<Pair<String, String>> = emptyList(),
    ): Result = withContext(Dispatchers.Default) {
        try {
            val engine = llm ?: run {
                if (!modelFile.exists()) {
                    return@withContext Result.Error(
                        "Model not found at ${modelFile.absolutePath}.\n" +
                        "Push via:\n" +
                        "  adb push gemma-3-1b-it-Q4_K_M.gguf /data/local/tmp/gemma-3-1b-it-Q4_K_M.gguf\n" +
                        "  adb shell run-as dev.dean.voice cp /data/local/tmp/gemma-3-1b-it-Q4_K_M.gguf /data/data/dev.dean.voice/files/gemma-3-1b-it-Q4_K_M.gguf\n" +
                        "  adb shell rm /data/local/tmp/gemma-3-1b-it-Q4_K_M.gguf"
                    )
                }
                Log.i(TAG, "Late-initializing LlmInference (warmup was not called)…")
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
                LlmInference.createFromOptions(context, options).also { llm = it }
            }

            val prompt = CleanupPrompts.build(intent, rawText, examples)
            Log.i(TAG, "Calling generateResponse (intent=${intent.displayName})…")
            val t0 = System.currentTimeMillis()
            val response = engine.generateResponse(prompt)
            val latency = System.currentTimeMillis() - t0
            val cleaned = stripPreamble(response.replace("\\n", "\n").trim())

            if (cleaned.isEmpty()) {
                return@withContext Result.Error("Gemma 3 returned empty response — prompt may have been filtered")
            }

            Log.i(TAG, "=== Cleanup (${latency}ms · ${intent.displayName}) ===")
            Log.i(TAG, "RAW:     $rawText")
            Log.i(TAG, "CLEANED: $cleaned")
            Log.i(TAG, "=================================================")

            Result.Success(rawText = rawText, cleanedText = cleaned, latencyMs = latency)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generateResponse failed", e)
            Result.Error(e.message ?: "Unknown inference error")
        }
    }

    private fun stripPreamble(text: String): String {
        val lines = text.lines()
        val firstContent = lines.indexOfFirst { line ->
            line.isNotBlank() && !line.trimEnd().endsWith(":")
        }
        return if (firstContent > 0) lines.drop(firstContent).joinToString("\n").trim() else text
    }

    fun close() {
        runCatching { llm?.close() }
        llm = null
    }
}
