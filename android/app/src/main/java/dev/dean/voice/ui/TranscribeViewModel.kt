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
import dev.dean.voice.data.settings.AppSettingsRepository
import dev.dean.voice.intent.VoiceIntent
import dev.dean.voice.model.CleanupModel
import dev.dean.voice.model.LlmCleanup
import dev.dean.voice.service.VoiceAccessibilityService
import dev.dean.voice.share.ShareSheetLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TranscribeViewModel(app: Application) : AndroidViewModel(app) {

    private val probe = SttProbe(app)
    private val cleanup = LlmCleanup(app)
    private val repository = (app as VoiceApp).repository
    private val settings = AppSettingsRepository(app)

    sealed interface UiState {
        data object Idle : UiState
        data object CheckingAvailability : UiState
        data object Downloading : UiState
        data object Recording : UiState
        data class Partial(val text: String) : UiState
        /** STT finished; transcript shown read-only while the user picks an intent. */
        data class ReviewTranscript(val rawText: String) : UiState
        /** STT finished; waiting on Gemma 4 E4B cleanup. */
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
            /** targetAppId → recency-weighted use count for this intent (0 if never used). */
            val useCounts: Map<String, Int> = emptyMap(),
        ) : UiState
        /** Text was injected directly into the target app via accessibility (dormant path). */
        data class Delivered(val appName: String) : UiState
        /** Injection failed or service unavailable — text is in clipboard. */
        data class ClipboardFallback(val appName: String) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    private val _intent = MutableStateFlow(VoiceIntent.AI_PROMPT)
    val intent: StateFlow<VoiceIntent> = _intent

    /** Active cleanup model, for the Settings model toggle. */
    private val _cleanupModel = MutableStateFlow(CleanupModel.E2B)
    val cleanupModel: StateFlow<CleanupModel> = _cleanupModel

    private var recordJob: Job? = null
    private val finalSegments = mutableListOf<String>()
    private var lastPartial: String = ""

    fun setIntent(intent: VoiceIntent) {
        _intent.value = intent
    }

    /** Call once on screen entry — loads the persisted model and warms up the engine. */
    fun warmupPromptModel() {
        viewModelScope.launch {
            val model = runCatching { CleanupModel.valueOf(settings.cleanupModel.first()) }
                .getOrDefault(CleanupModel.E2B)
            _cleanupModel.value = model
            cleanup.useModel(model)
        }
    }

    /** Switches the cleanup model (Settings toggle) — persists the choice and reloads the engine. */
    fun selectModel(model: CleanupModel) {
        if (model == _cleanupModel.value) return
        _cleanupModel.value = model
        viewModelScope.launch {
            settings.setCleanupModel(model.name)
            cleanup.useModel(model)
        }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // let the coroutine framework handle normal cancellation
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
                if (fullText.isNotBlank()) _state.value = UiState.ReviewTranscript(fullText)
            }
            is SttProbe.Event.Unavailable      -> _state.value =
                UiState.Error("AICore unavailable (status=${event.status})")
            is SttProbe.Event.Err              -> _state.value = UiState.Error(event.message)
        }
    }

    /**
     * Post-transcript entry point: the user has reviewed the captured text and
     * picked an intent. Applies that intent's processing instruction (cleanup),
     * then hands the result to the native share sheet.
     */
    fun chooseIntent(intent: VoiceIntent) {
        val rawText = (_state.value as? UiState.ReviewTranscript)?.rawText ?: return
        _intent.value = intent
        runCleanup(rawText)
    }

    private fun runCleanup(rawText: String) {
        val selectedIntent = _intent.value
        viewModelScope.launch {
            try {
                _state.value = UiState.Cleaning(rawText)

                // Phase 3: all intents now route through Gemma 4 E4B (including NOTES,
                // which the 1B model used to mangle with stray checkboxes). If the model
                // isn't on the device yet, fall back to fast programmatic cleanup so the
                // flow still works — graceful degradation, no error wall.
                if (cleanup.isModelReady()) {
                    when (val result = cleanup.clean(selectedIntent, rawText)) {
                        is LlmCleanup.Result.Success -> {
                            persistTranscription(result.rawText, result.cleanedText, selectedIntent, result.latencyMs)
                            shareCleaned(result.cleanedText, selectedIntent)
                        }
                        is LlmCleanup.Result.Error -> _state.value = UiState.Error(
                            "Cleanup failed: ${result.message}\n\nRaw STT: $rawText"
                        )
                    }
                } else {
                    val t0 = System.currentTimeMillis()
                    val cleaned = programmaticClean(rawText)
                    val latency = System.currentTimeMillis() - t0
                    persistTranscription(rawText, cleaned, selectedIntent, latency)
                    shareCleaned(cleaned, selectedIntent)
                }
            } catch (e: Exception) {
                _state.value = UiState.Error("Cleanup error: ${e.message}\n\nRaw STT: $rawText")
            }
        }
    }

    /**
     * Always copy the cleaned text to the clipboard, then hand it to the native
     * share sheet with intent-shaped targets. For Notes, a date/time heading is
     * prepended so the timestamp lands as the note title when pasted into Obsidian.
     */
    private fun shareCleaned(cleanedText: String, intent: VoiceIntent) {
        val delivered = if (intent == VoiceIntent.NOTES) prependTimestamp(cleanedText) else cleanedText
        val context: Context = getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("voice", delivered))
        ShareSheetLauncher.share(context, intent, delivered)
        // No confirmation toast — the share sheet is the user's confirmation; return to idle.
        _state.value = UiState.Idle
    }

    /** "# 2026-06-23 2.45 PM\n\n…" — filename-safe (no colon/slash) for the Obsidian title. */
    private fun prependTimestamp(text: String): String {
        val ts = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd h.mm a"))
        return "# $ts\n\n$text"
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
        val useCounts = ranked.associate { it.targetAppId to it.useCount }

        val installed = TargetAppRegistry.getInstalledApps(getApplication())

        // Reorder apps within each category: ranked first, then unranked
        val appsSorted = installed.mapValues { (_, apps) ->
            val inRank = apps.filter { it.id in rankedIds }
                .sortedByDescending { app -> ranked.first { it.targetAppId == app.id }.useCount }
            val unranked = apps.filter { it.id !in rankedIds }
            inRank + unranked
        }

        // Reorder categories based on intent — most relevant first
        val categoryOrder = when (intent) {
            VoiceIntent.AI_PROMPT -> listOf(
                TargetAppRegistry.Category.AI,
                TargetAppRegistry.Category.COMMS,
                TargetAppRegistry.Category.PKB,
            )
            VoiceIntent.TEXT -> listOf(
                TargetAppRegistry.Category.COMMS,
                TargetAppRegistry.Category.AI,
                TargetAppRegistry.Category.PKB,
            )
            VoiceIntent.EMAIL -> listOf(
                TargetAppRegistry.Category.COMMS,
                TargetAppRegistry.Category.PKB,
                TargetAppRegistry.Category.AI,
            )
            VoiceIntent.NOTES -> listOf(
                TargetAppRegistry.Category.PKB,
                TargetAppRegistry.Category.COMMS,
                TargetAppRegistry.Category.AI,
            )
        }

        val sorted = linkedMapOf<TargetAppRegistry.Category, List<TargetAppRegistry.TargetApp>>()
        categoryOrder.forEach { cat -> appsSorted[cat]?.let { sorted[cat] = it } }

        UiState.SelectTarget(cleanedText, intent, sorted, useCounts)
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
            .replaceSpokenPunctuation()
            .replace(Regex("\\s+([.!?,;:)])"), "$1")  // remove space before punctuation
            .replace(Regex("\\s+"), " ")
            .trim()
            .capitalizeSentences()
    }

    private fun String.capitalizeSentences(): String {
        // Capitalize after . ! ? at the start and after sentence-ending punctuation + whitespace
        return Regex("(^|[.!?]\\s+)(\\p{Ll})")
            .replace(this) { match ->
                match.groupValues[1] + match.groupValues[2].uppercase()
            }
    }

    private fun String.replaceSpokenPunctuation(): String {
        // Order matters — longer phrases before shorter ones
        return this
            .replace(Regex("\\bquestion mark\\b", RegexOption.IGNORE_CASE), "?")
            .replace(Regex("\\bexclamation (mark|point)\\b", RegexOption.IGNORE_CASE), "!")
            .replace(Regex("\\bopen (paren|parenthesis)\\b", RegexOption.IGNORE_CASE), "(")
            .replace(Regex("\\bclose (paren|parenthesis)\\b", RegexOption.IGNORE_CASE), ")")
            .replace(Regex("\\bnew (line|paragraph)\\b", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("\\bopen quote\\b", RegexOption.IGNORE_CASE), "\"")
            .replace(Regex("\\bclose quote\\b", RegexOption.IGNORE_CASE), "\"")
            .replace(Regex("\\bsemicolon\\b", RegexOption.IGNORE_CASE), ";")
            .replace(Regex("\\bcolon\\b", RegexOption.IGNORE_CASE), ":")
            .replace(Regex("\\bcomma\\b", RegexOption.IGNORE_CASE), ",")
            .replace(Regex("\\bdash\\b", RegexOption.IGNORE_CASE), "-")
            .replace(Regex("\\bellipsis\\b", RegexOption.IGNORE_CASE), "…")
            .replace(Regex("\\bperiod\\b", RegexOption.IGNORE_CASE), ".")
    }

    fun stopRecording() {
        val accumulated = (finalSegments + listOfNotNull(lastPartial.takeIf { it.isNotBlank() }))
            .joinToString(" ")
        recordJob?.cancel()
        viewModelScope.launch { probe.stop() }
        if (accumulated.isNotBlank()) {
            _state.value = UiState.ReviewTranscript(accumulated)
        } else {
            _state.value = UiState.Idle
        }
    }

    fun reset() {
        _state.value = UiState.Idle
    }

    override fun onCleared() {
        viewModelScope.launch { probe.stop() }
        probe.close()
        cleanup.close()
    }
}
