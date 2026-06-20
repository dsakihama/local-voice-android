package dev.dean.voice.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.FeatureStatus
import dev.dean.voice.audio.SttProbe
import dev.dean.voice.intent.VoiceIntent
import dev.dean.voice.model.MediaPipeLlmCleanup
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TranscribeViewModel(app: Application) : AndroidViewModel(app) {

    private val probe = SttProbe(app)
    private val cleanup = MediaPipeLlmCleanup(app)

    sealed interface UiState {
        data object Idle : UiState
        data object CheckingAvailability : UiState
        data object Downloading : UiState
        data object Recording : UiState
        data class Partial(val text: String) : UiState
        /** STT finished; waiting on Gemma 3 1B cleanup. */
        data class Cleaning(val rawText: String) : UiState
        /** Both STT and cleanup complete — ready for quality comparison. */
        data class Result(
            val rawText: String,
            val cleanedText: String,
            val latencyMs: Long,
            val intent: VoiceIntent,
        ) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    private val _intent = MutableStateFlow(VoiceIntent.AI_PROMPT)
    val intent: StateFlow<VoiceIntent> = _intent

    private var recordJob: Job? = null

    fun setIntent(intent: VoiceIntent) {
        _intent.value = intent
    }

    /** Call once on screen entry — warms up the inference engine while the user reads the UI. */
    fun warmupPromptModel() {
        viewModelScope.launch { cleanup.warmup() }
    }

    fun startRecording() {
        recordJob = viewModelScope.launch {
            _state.value = UiState.CheckingAvailability
            try {
                val sttStatus = probe.checkStatus()
                when (sttStatus) {
                    FeatureStatus.AVAILABLE -> Unit
                    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                        probe.download(::onProbeEvent)
                    }
                    else -> {
                        _state.value = UiState.Error(
                            "AICore STT unavailable (status=$sttStatus). Check bootloader lock."
                        )
                        return@launch
                    }
                }

                probe.startRecognition(::onProbeEvent)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun onProbeEvent(event: SttProbe.Event) {
        when (event) {
            is SttProbe.Event.Available        -> _state.value = UiState.CheckingAvailability
            is SttProbe.Event.Downloading      -> _state.value = UiState.Downloading
            is SttProbe.Event.DownloadProgress -> _state.value = UiState.Downloading
            is SttProbe.Event.DownloadComplete -> _state.value = UiState.CheckingAvailability
            is SttProbe.Event.Listening        -> _state.value = UiState.Recording
            is SttProbe.Event.Partial          -> _state.value = UiState.Partial(event.text)
            is SttProbe.Event.Final            -> runCleanup(event.text)
            is SttProbe.Event.Complete         -> Unit  // hold last state
            is SttProbe.Event.Unavailable      -> _state.value =
                UiState.Error("AICore unavailable (status=${event.status})")
            is SttProbe.Event.Err              -> _state.value = UiState.Error(event.message)
        }
    }

    private fun runCleanup(rawText: String) {
        val selectedIntent = _intent.value
        viewModelScope.launch {
            try {
                if (!cleanup.isModelReady()) {
                    _state.value = UiState.Error(
                        "Gemma 3 model not found.\n" +
                        "adb push gemma-3-1b-it-Q4_K_M.gguf /data/local/tmp/gemma-3-1b-it-Q4_K_M.gguf\n" +
                        "adb shell run-as dev.dean.voice cp /data/local/tmp/gemma-3-1b-it-Q4_K_M.gguf /data/data/dev.dean.voice/files/gemma-3-1b-it-Q4_K_M.gguf\n\n" +
                        "Raw STT: $rawText"
                    )
                    return@launch
                }

                _state.value = UiState.Cleaning(rawText)

                when (val result = cleanup.clean(selectedIntent, rawText)) {
                    is MediaPipeLlmCleanup.Result.Success -> _state.value = UiState.Result(
                        rawText = result.rawText,
                        cleanedText = result.cleanedText,
                        latencyMs = result.latencyMs,
                        intent = selectedIntent,
                    )
                    is MediaPipeLlmCleanup.Result.Error -> _state.value = UiState.Error(
                        "Cleanup failed: ${result.message}\n\nRaw STT: $rawText"
                    )
                }
            } catch (e: Exception) {
                _state.value = UiState.Error("Cleanup error: ${e.message}\n\nRaw STT: $rawText")
            }
        }
    }

    fun stopRecording() {
        recordJob?.cancel()
        viewModelScope.launch { probe.stop() }
        _state.value = UiState.Idle
    }

    fun reset() {
        _state.value = UiState.Idle
    }

    override fun onCleared() {
        viewModelScope.launch { probe.stop() }
        cleanup.close()
    }
}
