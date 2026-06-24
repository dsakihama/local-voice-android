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
 * On-device LLM cleanup models. Both `.litertlm` files can sit on the device at once;
 * switch between them at runtime (Settings gear) without rebuilding — useful for A/B'ing
 * latency vs faithfulness while iterating on prompts/agent files.
 */
enum class CleanupModel(val filename: String, val label: String) {
    E2B("gemma-4-E2B-it.litertlm", "Gemma 4 E2B · fast"),
    E4B("gemma-4-E4B-it.litertlm", "Gemma 4 E4B · faithful"),
}

/**
 * On-device text cleanup using the LiteRT-LM runtime + Gemma 4 (.litertlm).
 *
 * History:
 *  - Phase 2.2 — MediaPipe LLM Inference + Gemma 3 1B (1B made things up, not coachable).
 *  - Phase 3 — migrated to Gemma 4. MediaPipe `tasks-genai` had no working Gemma 4 path on
 *    the Pixel 10 Pro XL (GPU/OpenCL SIGBUS; CPU multi-minute). Switched the runtime to
 *    LiteRT-LM (`com.google.ai.edge.litertlm`). See design/litert-lm-migration.md.
 *
 * Backend note: the litertlm-android AAR bundles only the OpenCL/GL GPU accelerator
 * (libLiteRtClGlAccelerator.so) — no NPU/TPU dispatch. Backend.GPU() fails engine creation
 * for Gemma 4 on the Tensor G5, so we run on CPU (XNNPack).
 *
 * Model setup (one-time, dev) — download the .litertlm from HF litert-community, then push
 * to /data/data/dev.dean.voice/files/ via /data/local/tmp (see clean()'s error message for
 * the exact adb commands). Both E2B (~2.59 GB) and E4B (~3.66 GB) can be present at once.
 */
class LlmCleanup(private val context: Context) {

    companion object {
        private const val TAG = "LlmCleanup"

        // Total input+output token budget = KV-cache size. LiteRT-LM has no per-call output
        // cap, so this bounds runaway generation and avoids allocating the full 32K context.
        private const val MAX_NUM_TOKENS = 2048

        // GPU is out on this device: Backend.GPU() fails engine creation for both E2B and E4B
        // generic .litertlm (LiteRtLmJniException at llm_litert_compiled_model_executor.cc:1951)
        // — the bundled OpenCL accelerator can't compile Gemma 4 on the Tensor G5. So: CPU.
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

    @Volatile private var activeModel: CleanupModel = CleanupModel.E2B

    private val modelFile get() = File(context.filesDir, activeModel.filename)

    @Volatile private var engine: Engine? = null

    fun isModelReady(): Boolean = engine != null || modelFile.exists()

    /**
     * Switches the active model, reloading the engine if it changed. No-ops if [model] is
     * already active and loaded. Call from the ViewModel when the user picks a model or on
     * startup with the persisted choice.
     */
    suspend fun useModel(model: CleanupModel) {
        if (model == activeModel && engine != null) return
        if (model != activeModel) {
            close()
            activeModel = model
        }
        warmup()
    }

    /**
     * Loads the active model into memory so the first [clean] call doesn't block.
     * Safe to call multiple times — no-ops after the first successful load.
     */
    suspend fun warmup() {
        if (engine != null) return
        if (!modelFile.exists()) {
            Log.w(TAG, "Model not found at ${modelFile.absolutePath} — skipping warmup")
            return
        }
        Log.i(TAG, "Loading ${activeModel.filename} (LiteRT-LM, ${PREFERRED_BACKEND::class.simpleName}) from ${modelFile.absolutePath}…")
        try {
            withContext(Dispatchers.Default) { engine = newEngine() }
            Log.i(TAG, "${activeModel.filename} ready.")
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
                    val f = activeModel.filename
                    return@withContext Result.Error(
                        "Model not found at ${modelFile.absolutePath}.\n" +
                        "Push via:\n" +
                        "  adb push $f /data/local/tmp/$f\n" +
                        "  adb shell run-as dev.dean.voice cp /data/local/tmp/$f /data/data/dev.dean.voice/files/$f\n" +
                        "  adb shell rm /data/local/tmp/$f"
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

            Log.i(TAG, "sendMessage (model=${activeModel.name}, intent=${intent.displayName})…")
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

            Log.i(TAG, "=== Cleanup (${latency}ms · ${activeModel.name} · ${intent.displayName}) ===")
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
