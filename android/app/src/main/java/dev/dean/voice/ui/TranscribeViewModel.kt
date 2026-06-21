package dev.dean.voice.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.FeatureStatus
import dev.dean.voice.VoiceApp
import dev.dean.voice.audio.SttProbe
import dev.dean.voice.data.db.entities.Transcription
import dev.dean.voice.intent.VoiceIntent
import dev.dean.voice.model.MediaPipeLlmCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TranscribeViewModel(app: Application) : AndroidViewModel(app) {

    private val probe = SttProbe(app)
    private val cleanup = MediaPipeLlmCleanup(app)
    private val repository = (app as VoiceApp).repository

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
    private val finalSegments = mutableListOf<String>()
    private var lastPartial: String = ""

    fun setIntent(intent: VoiceIntent) {
        _intent.value = intent
    }

    /** Call once on screen entry — warms up the inference engine while the user reads the UI. */
    fun warmupPromptModel() {
        viewModelScope.launch { cleanup.warmup() }
    }

    fun startRecording() {
        finalSegments.clear()
        lastPartial = ""
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
            is SttProbe.Event.Partial          -> {
                lastPartial = event.text
                val accumulated = (finalSegments + event.text).joinToString(" ")
                _state.value = UiState.Partial(accumulated)
            }
            is SttProbe.Event.Final            -> {
                if (finalSegments.lastOrNull() != event.text) {
                    finalSegments.add(event.text)
                }
                lastPartial = ""
                val accumulated = finalSegments.joinToString(" ")
                _state.value = UiState.Partial(accumulated)
            }
            is SttProbe.Event.Complete         -> {
                val fullText = finalSegments.joinToString(" ")
                if (fullText.isNotBlank()) runCleanup(fullText)
            }
            is SttProbe.Event.Unavailable      -> _state.value =
                UiState.Error("AICore unavailable (status=${event.status})")
            is SttProbe.Event.Err              -> _state.value = UiState.Error(event.message)
        }
    }

    private fun runCleanup(rawText: String) {
        val selectedIntent = _intent.value
        viewModelScope.launch {
            try {
                _state.value = UiState.Cleaning(rawText)

                if (selectedIntent == VoiceIntent.AI_PROMPT) {
                    if (!cleanup.isModelReady()) {
                        _state.value = UiState.Error(
                            "Gemma 3 model not found.\n" +
                            "adb push gemma3-1B-it-int4.task /data/local/tmp/gemma3-1B-it-int4.task\n" +
                            "adb shell run-as dev.dean.voice cp /data/local/tmp/gemma3-1B-it-int4.task /data/data/dev.dean.voice/files/gemma3-1B-it-int4.task\n\n" +
                            "Raw STT: $rawText"
                        )
                        return@launch
                    }
                    when (val result = cleanup.clean(selectedIntent, rawText)) {
                        is MediaPipeLlmCleanup.Result.Success -> {
                            _state.value = UiState.Result(
                                rawText = result.rawText,
                                cleanedText = result.cleanedText,
                                latencyMs = result.latencyMs,
                                intent = selectedIntent,
                            )
                            persistTranscription(result.rawText, result.cleanedText, selectedIntent, result.latencyMs)
                        }
                        is MediaPipeLlmCleanup.Result.Error -> _state.value = UiState.Error(
                            "Cleanup failed: ${result.message}\n\nRaw STT: $rawText"
                        )
                    }
                } else {
                    val t0 = System.currentTimeMillis()
                    val cleaned = programmaticClean(rawText)
                    val latency = System.currentTimeMillis() - t0
                    _state.value = UiState.Result(
                        rawText = rawText,
                        cleanedText = cleaned,
                        latencyMs = latency,
                        intent = selectedIntent,
                    )
                    persistTranscription(rawText, cleaned, selectedIntent, latency)
                }
            } catch (e: Exception) {
                _state.value = UiState.Error("Cleanup error: ${e.message}\n\nRaw STT: $rawText")
            }
        }
    }

    private fun persistTranscription(raw: String, cleaned: String, intent: VoiceIntent, latencyMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.saveTranscription(
                    Transcription(
                        rawText = raw,
                        cleanedText = cleaned,
                        intent = intent.name,
                        processingTimeMs = latencyMs.toInt(),
                        audioLengthMs = 0,
                    )
                )
                android.util.Log.i("VoiceRepository", "Transcription saved — intent=${intent.name}")
            }.onFailure {
                android.util.Log.e("VoiceRepository", "Failed to save transcription", it)
            }
        }
    }

    private fun programmaticClean(text: String): String {
        return text
            .replace(Regex("\\b(umm+|uh+|um+)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { it.uppercase() }
    }

    fun stopRecording() {
        val accumulated = (finalSegments + listOfNotNull(lastPartial.takeIf { it.isNotBlank() }))
            .joinToString(" ")
        recordJob?.cancel()
        viewModelScope.launch { probe.stop() }
        if (accumulated.isNotBlank()) {
            runCleanup(accumulated)
        } else {
            _state.value = UiState.Idle
        }
    }

    fun reset() {
        _state.value = UiState.Idle
    }

    override fun onCleared() {
        viewModelScope.launch { probe.stop() }
        cleanup.close()
    }
}
