package dev.dean.voice.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.FeatureStatus
import dev.dean.voice.VoiceApp
import dev.dean.voice.apps.TargetAppRegistry
import dev.dean.voice.audio.SttProbe
import dev.dean.voice.data.db.entities.AppUsageRecord
import dev.dean.voice.data.db.entities.Transcription
import dev.dean.voice.intent.VoiceIntent
import dev.dean.voice.model.MediaPipeLlmCleanup
import dev.dean.voice.service.VoiceAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

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
        /** Cleanup done; user is choosing a target app. */
        data class SelectTarget(
            val cleanedText: String,
            val intent: VoiceIntent,
            val rankedApps: Map<TargetAppRegistry.Category, List<TargetAppRegistry.TargetApp>>,
        ) : UiState
        /** Text was injected directly into the target app via accessibility. */
        data class Delivered(val appName: String) : UiState
        /** Injection failed or service unavailable — text is in clipboard. */
        data class ClipboardFallback(val appName: String) : UiState
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
                            persistTranscription(result.rawText, result.cleanedText, selectedIntent, result.latencyMs)
                            _state.value = buildSelectTarget(result.cleanedText, selectedIntent)
                        }
                        is MediaPipeLlmCleanup.Result.Error -> _state.value = UiState.Error(
                            "Cleanup failed: ${result.message}\n\nRaw STT: $rawText"
                        )
                    }
                } else {
                    val t0 = System.currentTimeMillis()
                    val cleaned = programmaticClean(rawText)
                    val latency = System.currentTimeMillis() - t0
                    persistTranscription(rawText, cleaned, selectedIntent, latency)
                    _state.value = buildSelectTarget(cleaned, selectedIntent)
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

    private suspend fun buildSelectTarget(cleanedText: String, intent: VoiceIntent): UiState.SelectTarget =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
        val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        val ranked = repository.getRankedApps(intent.name, thirtyDaysAgo)
        val rankedIds = ranked.map { it.targetAppId }.toSet()

        val installed = TargetAppRegistry.getInstalledApps(getApplication())

        // Rebuild the map: ranked apps first (in frequency order), then unranked installed apps
        val sorted = installed.mapValues { (_, apps) ->
            val inRank = apps.filter { it.id in rankedIds }
                .sortedByDescending { app -> ranked.first { it.targetAppId == app.id }.useCount }
            val unranked = apps.filter { it.id !in rankedIds }
            inRank + unranked
        }

        UiState.SelectTarget(cleanedText, intent, sorted)
    }

    fun deliverTo(app: TargetAppRegistry.TargetApp) {
        val currentState = _state.value as? UiState.SelectTarget ?: return
        val text = currentState.cleanedText
        val intent = currentState.intent
        val context: Context = getApplication()

        viewModelScope.launch(Dispatchers.IO) {
            val service = VoiceAccessibilityService.instance.value
            val injected = service?.injectText(text) ?: false

            val accepted = injected  // if injection succeeded, count as accepted
            repository.saveAppUsage(
                AppUsageRecord(
                    targetAppId = app.id,
                    targetAppName = app.displayName,
                    intent = intent.name,
                    userAccepted = accepted,
                )
            )

            if (!injected) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("voice", text))
            }

            _state.value = if (injected) UiState.Delivered(app.displayName)
                           else UiState.ClipboardFallback(app.displayName)

            // Launch the target app on the main thread after state update
            launch(Dispatchers.Main) {
                launchApp(context, app)
            }
        }
    }

    private fun launchApp(context: Context, app: TargetAppRegistry.TargetApp) {
        val launchIntent: Intent? = if (app.webFallbackUrl != null) {
            // Web-only apps — open in Chrome specifically
            context.packageManager.getLaunchIntentForPackage("com.android.chrome")
                ?.apply { data = Uri.parse(app.webFallbackUrl) }
                ?: Intent(Intent.ACTION_VIEW, Uri.parse(app.webFallbackUrl))
        } else {
            context.packageManager.getLaunchIntentForPackage(app.id)
        }
        launchIntent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
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
