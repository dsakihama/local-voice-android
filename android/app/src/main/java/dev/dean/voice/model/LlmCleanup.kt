package dev.dean.voice.model

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import dev.dean.voice.intent.CleanupPrompts
import dev.dean.voice.intent.VoiceIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device text cleanup using the LiteRT-LM runtime + Gemma 4 E4B (.litertlm).
 *
 * History:
 *  - Phase 2.2 — MediaPipe LLM Inference + Gemma 3 1B (1B made things up, not coachable).
 *  - Phase 3 — migrated to Gemma 4 E4B. MediaPipe `tasks-genai` had no working Gemma 4
 *    path on the Pixel 10 Pro XL (GPU/OpenCL SIGBUS; CPU multi-minute). Switched the
 *    runtime to LiteRT-LM (`com.google.ai.edge.litertlm`), which is what Gemma 4 .litertlm
 *    is built for and which drives the GPU. See design/litert-lm-migration.md.
 *
 * Model setup (one-time, dev) — download the [MODEL_FILENAME] .litertlm from
 * HF litert-community (E2B generic ~2.59 GB / E4B generic ~3.66 GB), then push the
 * file to /data/data/dev.dean.voice/files/ via /data/local/tmp (see clean()'s error
 * message for the exact adb commands).
 *
 * Backend note: the litertlm-android AAR bundles only the OpenCL/GL GPU accelerator
 * (libLiteRtClGlAccelerator.so) — no NPU/TPU dispatch. So the Tensor G5 AOT build is
 * not runnable here; the accelerated path is a generic .litertlm on Backend.GPU().
 */
class LlmCleanup(private val context: Context) {

    companion object {
        private const val TAG = "LlmCleanup"
        private const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"

        // Total input+output token budget = KV-cache size. LiteRT-LM has no per-call output
        // cap, so this bounds runaway generation and avoids allocating the full 32K context.
        private const val MAX_NUM_TOKENS = 2048

        // GPU is out on this device: Backend.GPU() fails engine creation for BOTH E2B and E4B
        // generic .litertlm (LiteRtLmJniException at llm_litert_compiled_model_executor.cc:1951)
        // — the bundled OpenCL accelerator can't compile Gemma 4 on the Tensor G5. So: CPU
        // (XNNPack). Works; slower. NPU/TPU would need a Google Tensor dispatch lib not in the AAR.
        private val PREFERRED_BACKEND: Backend = Backend.CPU()

        // Greedy decoding for faithful cleanup — minimizes the "making stuff up" failure mode.
        // topK=1 forces argmax (topP/temperature then don't matter), kept in valid ranges.
        private val SAMPLER = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
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

    @Volatile private var engine: Engine? = null

    fun isModelReady(): Boolean = engine != null || modelFile.exists()

    /**
     * Loads the model into memory so the first [clean] call doesn't block.
     * Safe to call multiple times — no-ops after the first successful load.
     * Call once after the UI is shown, before the user starts recording.
     */
    suspend fun warmup() {
        if (engine != null) return
        if (!modelFile.exists()) {
            Log.w(TAG, "Model not found at ${modelFile.absolutePath} — skipping warmup")
            return
        }
        Log.i(TAG, "Loading $MODEL_FILENAME (LiteRT-LM, ${PREFERRED_BACKEND::class.simpleName}) from ${modelFile.absolutePath}…")
        try {
            withContext(Dispatchers.Default) { engine = newEngine() }
            Log.i(TAG, "$MODEL_FILENAME ready.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed: ${e.message}", e)
        }
    }

    /**
     * Cleans [rawText] with the prompt template for [intent]. Initializes the engine on
     * first call if [warmup] was not called beforehand. Each call runs in a fresh
     * conversation so requests stay independent (no history bleed between cleanups).
     */
    suspend fun clean(
        intent: VoiceIntent,
        rawText: String,
        examples: List<Pair<String, String>> = emptyList(),
    ): Result = withContext(Dispatchers.Default) {
        try {
            val activeEngine = engine ?: run {
                if (!modelFile.exists()) {
                    return@withContext Result.Error(
                        "Model not found at ${modelFile.absolutePath}.\n" +
                        "Push via:\n" +
                        "  adb push $MODEL_FILENAME /data/local/tmp/$MODEL_FILENAME\n" +
                        "  adb shell run-as dev.dean.voice cp /data/local/tmp/$MODEL_FILENAME /data/data/dev.dean.voice/files/$MODEL_FILENAME\n" +
                        "  adb shell rm /data/local/tmp/$MODEL_FILENAME"
                    )
                }
                Log.i(TAG, "Late-initializing engine (warmup was not called)…")
                newEngine().also { engine = it }
            }

            val (system, user) = CleanupPrompts.buildParts(intent, rawText, examples)
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SAMPLER,
                automaticToolCalling = false,
            )

            Log.i(TAG, "sendMessage (intent=${intent.displayName})…")
            val t0 = System.currentTimeMillis()
            val response = activeEngine.createConversation(conversationConfig).use { conversation ->
                // sendMessage returns a Message; Contents.toString() joins its text parts.
                conversation.sendMessage(user).contents.toString()
            }
            val latency = System.currentTimeMillis() - t0
            val cleaned = stripPreamble(response.replace("\\n", "\n").trim())

            if (cleaned.isEmpty()) {
                return@withContext Result.Error("Gemma 4 returned empty response — prompt may have been filtered")
            }

            Log.i(TAG, "=== Cleanup (${latency}ms · ${intent.displayName}) ===")
            Log.i(TAG, "RAW:     $rawText")
            Log.i(TAG, "CLEANED: $cleaned")
            Log.i(TAG, "=================================================")

            Result.Success(rawText = rawText, cleanedText = cleaned, latencyMs = latency)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
            Result.Error(e.message ?: "Unknown inference error")
        }
    }

    private fun newEngine(): Engine =
        Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = PREFERRED_BACKEND,
                maxNumTokens = MAX_NUM_TOKENS,
                cacheDir = context.cacheDir.absolutePath,
            )
        ).also { it.initialize() }

    private fun stripPreamble(text: String): String {
        val lines = text.lines()
        val firstContent = lines.indexOfFirst { line ->
            line.isNotBlank() && !line.trimEnd().endsWith(":")
        }
        return if (firstContent > 0) lines.drop(firstContent).joinToString("\n").trim() else text
    }

    fun close() {
        runCatching { engine?.close() }
        engine = null
    }
}
