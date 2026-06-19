package dev.dean.voice.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.FeatureStatus
import dev.dean.voice.audio.SttProbe
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TranscribeViewModel(app: Application) : AndroidViewModel(app) {

    private val probe = SttProbe(app)

    sealed interface UiState {
        data object Idle : UiState
        data object CheckingAvailability : UiState
        data object Downloading : UiState
        data object Recording : UiState
        data class Partial(val text: String) : UiState
        data class Result(val text: String) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    private var recordJob: Job? = null

    fun startRecording() {
        recordJob = viewModelScope.launch {
            _state.value = UiState.CheckingAvailability
            try {
                val status = probe.checkStatus()
                when (status) {
                    FeatureStatus.AVAILABLE -> {
                        probe.startRecognition(::onProbeEvent)
                    }
                    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                        probe.download(::onProbeEvent)
                        probe.startRecognition(::onProbeEvent)
                    }
                    else -> {
                        _state.value = UiState.Error("AICore unavailable (status=$status). Check bootloader lock.")
                    }
                }
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun onProbeEvent(event: SttProbe.Event) {
        _state.value = when (event) {
            is SttProbe.Event.Available       -> UiState.CheckingAvailability
            is SttProbe.Event.Downloading     -> UiState.Downloading
            is SttProbe.Event.DownloadProgress -> UiState.Downloading
            is SttProbe.Event.DownloadComplete -> UiState.CheckingAvailability
            is SttProbe.Event.Listening       -> UiState.Recording
            is SttProbe.Event.Partial         -> UiState.Partial(event.text)
            is SttProbe.Event.Final           -> UiState.Result(event.text)
            is SttProbe.Event.Complete        -> _state.value  // hold last result
            is SttProbe.Event.Unavailable     -> UiState.Error("AICore unavailable (status=${event.status})")
            is SttProbe.Event.Err             -> UiState.Error(event.message)
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
    }
}
