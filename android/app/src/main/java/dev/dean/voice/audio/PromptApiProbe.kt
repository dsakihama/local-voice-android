package dev.dean.voice.audio

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import dev.dean.voice.intent.CleanupPrompts
import dev.dean.voice.intent.VoiceIntent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Temporary probe: re-tests ML Kit GenAI Prompt API (generateContent) on Pixel 10 Pro XL.
 * Abandoned 2026-06-19 when generateContent() never returned. Re-testing with genai-prompt Beta.
 * All output is logcat-only under tag "PromptApiProbe". No production paths are touched.
 */
class PromptApiProbe {

    companion object {
        const val TAG = "PromptApiProbe"
        private const val TIMEOUT_MS = 30_000L

        // ~50-word filler-laden input matching real dictation patterns
        private const val TEST_INPUT =
            "umm so I was thinking about uh the project deadline and like we should " +
            "probably move it to next Friday because the team is still working on the " +
            "main features and we haven't done any uh testing yet and I think we need " +
            "like at least a few more days to finish everything up"
    }

    private val model by lazy { Generation.getClient() }

    suspend fun runProbe() {
        Log.i(TAG, "=== PromptApiProbe starting ===")

        val status = runCatching { model.checkStatus() }.getOrElse { e ->
            Log.e(TAG, "checkStatus() threw: ${e.message}")
            return
        }
        Log.i(TAG, "feature status=$status")

        when (status) {
            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING -> {
                Log.i(TAG, "model not on device — downloading...")
                var downloadOk = true
                model.download().collect { ds ->
                    when (ds) {
                        is DownloadStatus.DownloadStarted -> Log.i(TAG, "download started")
                        is DownloadStatus.DownloadProgress ->
                            Log.i(TAG, "download ${ds.totalBytesDownloaded} bytes")
                        is DownloadStatus.DownloadCompleted -> Log.i(TAG, "download complete")
                        is DownloadStatus.DownloadFailed -> {
                            Log.e(TAG, "download failed")
                            downloadOk = false
                        }
                    }
                }
                if (!downloadOk) {
                    Log.w(TAG, "=== PromptApiProbe aborted (download failed) ===")
                    return
                }
            }
            FeatureStatus.UNAVAILABLE -> {
                Log.w(TAG, "UNAVAILABLE — device not supported or AICore not configured")
                Log.i(TAG, "=== PromptApiProbe done ===")
                return
            }
            // FeatureStatus.AVAILABLE — proceed
        }

        val (prefix, userContent) = CleanupPrompts.buildParts(VoiceIntent.TEXT, TEST_INPUT)
        Log.i(TAG, "calling generateContent() — input=${TEST_INPUT.length} chars prefix=${prefix.length} chars")

        val t0 = System.currentTimeMillis()
        try {
            withTimeout(TIMEOUT_MS) {
                val response = model.generateContent(
                    generateContentRequest(TextPart(userContent)) {
                        promptPrefix = PromptPrefix(prefix)
                    }
                )
                val totalMs = System.currentTimeMillis() - t0
                // generateContent is blocking; ttfr == total (use generateContentStream for first-token timing)
                // GenerateContentResponse.candidates[0].text is the text accessor (no top-level .text)
                val outputText = response.candidates.firstOrNull()?.text ?: "(no candidates)"
                Log.i(TAG, "status=$status ttfr=${totalMs}ms total=${totalMs}ms output=$outputText")
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "TIMEOUT — generateContent did not return within ${TIMEOUT_MS}ms (same failure mode as 2026-06-19?)")
        } catch (e: Exception) {
            Log.e(TAG, "generateContent threw: ${e.message}", e)
        }

        Log.i(TAG, "=== PromptApiProbe done ===")
    }
}
