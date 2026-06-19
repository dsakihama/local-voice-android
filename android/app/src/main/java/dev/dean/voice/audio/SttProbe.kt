package dev.dean.voice.audio

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import java.util.Locale

/**
 * Standalone probe: validates ML Kit GenAI Speech Recognition (Advanced mode) on Pixel 10 Pro XL.
 * Phase 2.1 validation — results exposed as sealed Event for ViewModel to drive UI state.
 */
class SttProbe(private val context: Context) {

    companion object {
        private const val TAG = "SttProbe"
    }

    sealed interface Event {
        data object Available : Event
        data object Downloading : Event
        data class DownloadProgress(val bytesDownloaded: Long) : Event
        data object DownloadComplete : Event
        data object Listening : Event
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data object Complete : Event
        data class Unavailable(val status: Int) : Event
        data class Err(val message: String) : Event
    }

    private val recognizer: SpeechRecognizer by lazy {
        val options: SpeechRecognizerOptions = speechRecognizerOptions {
            locale = Locale.US
            preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
        }
        SpeechRecognition.getClient(options)
    }

    suspend fun checkStatus(): Int {
        Log.i(TAG, "Checking AICore feature status...")
        return recognizer.checkStatus().also { Log.i(TAG, "Status: $it") }
    }

    suspend fun download(onEvent: (Event) -> Unit) {
        recognizer.download().collect { downloadStatus ->
            when (downloadStatus) {
                is DownloadStatus.DownloadStarted -> {
                    Log.i(TAG, "Download started.")
                    onEvent(Event.Downloading)
                }
                is DownloadStatus.DownloadProgress -> {
                    Log.i(TAG, "Download: ${downloadStatus.totalBytesDownloaded} bytes")
                    onEvent(Event.DownloadProgress(downloadStatus.totalBytesDownloaded))
                }
                is DownloadStatus.DownloadCompleted -> {
                    Log.i(TAG, "Download complete.")
                    onEvent(Event.DownloadComplete)
                }
                is DownloadStatus.DownloadFailed -> {
                    Log.e(TAG, "Download failed.")
                    onEvent(Event.Err("Model download failed."))
                }
            }
        }
    }

    @Suppress("MissingPermission")
    suspend fun startRecognition(onEvent: (Event) -> Unit) {
        Log.i(TAG, "Starting recognition — speak now.")
        onEvent(Event.Listening)
        val request = speechRecognizerRequest {
            audioSource = AudioSource.fromMic()
        }
        recognizer.startRecognition(request).collect { response ->
            when (response) {
                is SpeechRecognizerResponse.PartialTextResponse -> {
                    Log.d(TAG, "Partial: ${response.text}")
                    onEvent(Event.Partial(response.text))
                }
                is SpeechRecognizerResponse.FinalTextResponse -> {
                    Log.i(TAG, "Final: ${response.text}")
                    onEvent(Event.Final(response.text))
                }
                is SpeechRecognizerResponse.CompletedResponse -> {
                    Log.i(TAG, "Session complete.")
                    onEvent(Event.Complete)
                }
                is SpeechRecognizerResponse.ErrorResponse -> {
                    Log.e(TAG, "Error: ${response.e}")
                    onEvent(Event.Err(response.e.message ?: "Recognition error"))
                }
            }
        }
    }

    suspend fun stop() {
        recognizer.stopRecognition()
        recognizer.close()
    }
}
