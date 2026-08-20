package dev.dean.voice.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dean.voice.BuildConfig
import dev.dean.voice.apps.TargetAppRegistry
import dev.dean.voice.intent.VoiceIntent
import dev.dean.voice.model.CleanupModel
import dev.dean.voice.overlay.VoiceOverlayService
import dev.dean.voice.ui.theme.Jarvis
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: TranscribeViewModel = viewModel(),
    autoRecord: Boolean = false,
    onAutoRecordConsumed: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val selectedIntent by vm.intent.collectAsState()
    val cleanupModel by vm.cleanupModel.collectAsState()

    // Elapsed-time ticker for the REC indicator while listening.
    val listening = state is TranscribeViewModel.UiState.Recording ||
        state is TranscribeViewModel.UiState.Partial
    var elapsedSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(listening) {
        if (listening) {
            elapsedSec = 0
            while (true) {
                delay(1_000)
                elapsedSec++
            }
        }
    }

    LaunchedEffect(Unit) { vm.warmupPromptModel() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startRecording()
    }

    // Launched from the floating bubble — start capturing as soon as we're idle.
    LaunchedEffect(autoRecord, state) {
        if (autoRecord && state is TranscribeViewModel.UiState.Idle) {
            onAutoRecordConsumed()
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Intent picker — shown after capture, over the transcript. Choosing an intent
    // applies its cleanup and hands the result to the native share sheet.
    val reviewState = state as? TranscribeViewModel.UiState.ReviewTranscript
    if (reviewState != null) {
        val intentSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { vm.reset() },
            sheetState = intentSheetState,
            containerColor = Jarvis.Deep,
        ) {
            IntentSheet(
                selected = selectedIntent,
                onPick = { intent -> vm.chooseIntent(intent) },
            )
        }
    }

    // Bottom sheet hoisted outside Scaffold so it overlays correctly
    val selectTargetState = state as? TranscribeViewModel.UiState.SelectTarget
    if (selectTargetState != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { vm.reset() },
            sheetState = sheetState,
            containerColor = Jarvis.Deep,
        ) {
            SelectTargetSheet(
                state = selectTargetState,
                onAppSelected = vm::deliverTo,
                onDismiss = { vm.reset() },
            )
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            AppHeader(
                currentModel = cleanupModel,
                onSelectModel = vm::selectModel,
                onProbePromptApi = vm::runPromptApiProbe,
            )
            Spacer(Modifier.height(48.dp))

            when (val s = state) {
                is TranscribeViewModel.UiState.Idle -> {
                    VoiceOrb(
                        state = OrbState.Idle,
                        onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    )
                    OrbCaption("Tap to dictate", "Speak, then choose where it goes")
                    Spacer(Modifier.height(40.dp))
                    HorizontalDivider(color = Jarvis.Line)
                    Spacer(Modifier.height(20.dp))
                    FloatingButtonControl()
                }

                is TranscribeViewModel.UiState.CheckingAvailability -> {
                    VoiceOrb(state = OrbState.Processing, onClick = {})
                    OrbCaption("Checking AICore…", "One moment")
                }

                is TranscribeViewModel.UiState.Downloading -> {
                    VoiceOrb(state = OrbState.Processing, onClick = {})
                    OrbCaption("Downloading model…", "First run only")
                }

                is TranscribeViewModel.UiState.Recording -> {
                    RecKicker(elapsedSec)
                    ListeningWave(modifier = Modifier.clickable { vm.stopRecording() })
                    OrbCaption("Listening", "Tap anywhere to stop")
                }

                is TranscribeViewModel.UiState.Partial -> {
                    RecKicker(elapsedSec)
                    ListeningWave(modifier = Modifier.clickable { vm.stopRecording() })
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = s.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Jarvis.Frost,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "tap the wave to stop",
                        style = MaterialTheme.typography.bodySmall,
                        color = Jarvis.Mist,
                    )
                }

                is TranscribeViewModel.UiState.ReviewTranscript -> {
                    // Intent picker is hoisted above Scaffold — show captured text behind it.
                    Kicker("CAPTURED")
                    Spacer(Modifier.height(16.dp))
                    OutputCard(label = "TRANSCRIPT", text = s.rawText, accent = false)
                }

                is TranscribeViewModel.UiState.Cleaning -> {
                    ProcessingSteps()
                }

                is TranscribeViewModel.UiState.Result -> {
                    // Quality comparison panel — kept until model quality is stable enough to skip.
                    // TODO: remove once UiState.Result is no longer needed (see tracker.md Phase 5).
                    Kicker("CLEANED · ${s.latencyMs}MS · ${s.intent.displayName.uppercase()}")
                    Spacer(Modifier.height(16.dp))
                    OutputCard(label = "RAW", text = s.rawText, accent = false)
                    Spacer(Modifier.height(12.dp))
                    OutputCard(label = "CLEANED", text = s.cleanedText, accent = true)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { vm.reset() }) { Text("Record again") }
                }

                is TranscribeViewModel.UiState.SelectTarget -> {
                    // Sheet is hoisted above Scaffold — show cleaned text behind it
                    Text(
                        text = s.cleanedText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Jarvis.Mist,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }

                is TranscribeViewModel.UiState.Delivered -> {
                    LaunchedEffect(s) { delay(2_000); vm.reset() }
                    StatusToast(Icons.Filled.CheckCircle, Jarvis.Ok, "Sent to ${s.appName}")
                }

                is TranscribeViewModel.UiState.ClipboardFallback -> {
                    LaunchedEffect(s) { delay(2_000); vm.reset() }
                    StatusToast(Icons.Filled.ContentPaste, Jarvis.Signal, "Copied — paste in ${s.appName}")
                }

                is TranscribeViewModel.UiState.Error -> {
                    VoiceOrb(state = OrbState.Disabled, onClick = {})
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = s.message,
                        color = Jarvis.Crit,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { vm.reset() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Text("Try again") }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

// ── Header ───────────────────────────────────────────────────────────────────

@Composable
private fun AppHeader(
    currentModel: CleanupModel,
    onSelectModel: (CleanupModel) -> Unit,
    onProbePromptApi: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Jarvis.Signal, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = Jarvis.Void, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(12.dp))
            Text(
                "Local Voice",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Jarvis.Frost,
            )
        }
        Box {
            var menuOpen by remember { mutableStateOf(false) }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Cleanup model", tint = Jarvis.Mist)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                CleanupModel.entries.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(if (model == currentModel) "✓  ${model.label}" else "     ${model.label}") },
                        onClick = {
                            onSelectModel(model)
                            menuOpen = false
                        },
                    )
                }
                if (BuildConfig.DEBUG) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Probe Prompt API") },
                        onClick = {
                            menuOpen = false
                            onProbePromptApi()
                        },
                    )
                }
            }
        }
    }
}

// ── Small building blocks ────────────────────────────────────────────────────

@Composable
private fun OrbCaption(title: String, subtitle: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Jarvis.Frost)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Jarvis.Mist, textAlign = TextAlign.Center)
}

@Composable
private fun Kicker(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 2.sp,
        color = Jarvis.Signal,
    )
}

@Composable
private fun RecKicker(elapsedSec: Int) {
    val mm = elapsedSec / 60
    val ss = (elapsedSec % 60).toString().padStart(2, '0')
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(Jarvis.Signal, RoundedCornerShape(50)))
        Spacer(Modifier.size(8.dp))
        Kicker("REC · $mm:$ss")
    }
    Spacer(Modifier.height(20.dp))
}

/** Processing checklist for the cleanup phase (design s4, right). */
@Composable
private fun ProcessingSteps() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Kicker("WORKING")
        Spacer(Modifier.height(10.dp))
        Text("Tidying up your words", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Jarvis.Frost)
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
            color = Jarvis.Signal,
            trackColor = Jarvis.Slate,
        )
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StepRow(StepState.Done, "Captured audio")
            StepRow(StepState.Done, "Transcribed on-device")
            StepRow(StepState.Active, "Polishing your words")
        }
    }
}

private enum class StepState { Done, Active, Pending }

@Composable
private fun StepRow(state: StepState, label: String) {
    val icon = when (state) {
        StepState.Done -> Icons.Filled.CheckCircle
        StepState.Active -> Icons.Filled.RadioButtonChecked
        StepState.Pending -> Icons.Filled.RadioButtonUnchecked
    }
    val tint = if (state == StepState.Pending) Jarvis.Muted else Jarvis.Signal
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = when (state) {
                StepState.Active -> Jarvis.Frost
                StepState.Done -> Jarvis.Mist
                StepState.Pending -> Jarvis.Muted
            },
            fontWeight = if (state == StepState.Active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun StatusToast(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
) {
    Row(
        modifier = Modifier
            .background(Jarvis.Slate, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = Jarvis.Frost)
    }
}

@Composable
private fun OutputCard(label: String, text: String, accent: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (accent) Jarvis.SignalDim else Jarvis.Slate,
                RoundedCornerShape(10.dp),
            )
            .padding(16.dp),
    ) {
        Kicker(label)
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Jarvis.Frost,
        )
    }
}

// ── Target picker sheet ──────────────────────────────────────────────────────

@Composable
private fun SelectTargetSheet(
    state: TranscribeViewModel.UiState.SelectTarget,
    onAppSelected: (TargetAppRegistry.TargetApp) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Send to…",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Jarvis.Frost,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Sorted by how often you use it.",
            style = MaterialTheme.typography.bodyMedium,
            color = Jarvis.Mist,
        )
        Spacer(Modifier.height(20.dp))

        if (state.rankedApps.all { it.value.isEmpty() }) {
            Text(
                "No supported apps found. Text is copied to clipboard.",
                style = MaterialTheme.typography.bodyMedium,
                color = Jarvis.Mist,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
        }

        state.rankedApps.forEach { (category, apps) ->
            if (apps.isEmpty()) return@forEach

            Kicker(category.displayName.uppercase())
            Spacer(Modifier.height(10.dp))
            AppGrid(apps = apps, useCounts = state.useCounts, onSelect = onAppSelected)
            Spacer(Modifier.height(20.dp))
        }

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text("Cancel") }
    }
}

/** Three-column grid of target-app cards, each showing its recency-weighted use count. */
@Composable
private fun AppGrid(
    apps: List<TargetAppRegistry.TargetApp>,
    useCounts: Map<String, Int>,
    onSelect: (TargetAppRegistry.TargetApp) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        apps.chunked(3).forEach { rowApps ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowApps.forEach { app ->
                    AppCard(
                        modifier = Modifier.weight(1f),
                        app = app,
                        useCount = useCounts[app.id] ?: 0,
                        onClick = { onSelect(app) },
                    )
                }
                repeat(3 - rowApps.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppCard(
    modifier: Modifier,
    app: TargetAppRegistry.TargetApp,
    useCount: Int,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Jarvis.Slate)
            .border(1.dp, Jarvis.Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Letter avatar
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Jarvis.Slate2),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.displayName.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Jarvis.Frost,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            app.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = Jarvis.Frost,
            fontWeight = FontWeight.Medium,
        )
        if (useCount > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                "${useCount}×",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Jarvis.Mist,
            )
        }
    }
}

// ── Intent picker sheet ──────────────────────────────────────────────────────

/** Subtitle shown under each intent in the picker. */
private fun intentBlurb(intent: VoiceIntent): String = when (intent) {
    VoiceIntent.AI_PROMPT -> "Claude, ChatGPT"
    VoiceIntent.TEXT -> "Messages & chat"
    VoiceIntent.EMAIL -> "Compose & reply"
    VoiceIntent.NOTES -> "Jot & capture"
}

private fun intentIcon(intent: VoiceIntent): androidx.compose.ui.graphics.vector.ImageVector = when (intent) {
    VoiceIntent.AI_PROMPT -> Icons.Filled.AutoAwesome
    VoiceIntent.TEXT -> Icons.Filled.Chat
    VoiceIntent.EMAIL -> Icons.Filled.Email
    VoiceIntent.NOTES -> Icons.Filled.Description
}

@Composable
private fun IntentSheet(
    selected: VoiceIntent,
    onPick: (VoiceIntent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        Kicker("WHAT'S THIS FOR?")
        Spacer(Modifier.height(10.dp))
        Text("Where's this going?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Jarvis.Frost)
        Spacer(Modifier.height(6.dp))
        Text("Intent shapes the cleanup and the share targets.", style = MaterialTheme.typography.bodyMedium, color = Jarvis.Mist)
        Spacer(Modifier.height(20.dp))

        VoiceIntent.entries.chunked(2).forEach { rowIntents ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowIntents.forEach { intent ->
                    IntentCard(
                        modifier = Modifier.weight(1f),
                        intent = intent,
                        selected = intent == selected,
                        onClick = { onPick(intent) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun IntentCard(
    modifier: Modifier,
    intent: VoiceIntent,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (selected) Jarvis.Signal else Jarvis.Mist
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Jarvis.SignalFaint else Jarvis.Slate)
            .border(1.dp, if (selected) Jarvis.Signal else Jarvis.Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(intentIcon(intent), contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = Jarvis.Signal, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(intent.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Jarvis.Frost)
        Spacer(Modifier.height(2.dp))
        Text(intentBlurb(intent), style = MaterialTheme.typography.bodySmall, color = Jarvis.Mist)
    }
}

// ── Floating bubble control ──────────────────────────────────────────────────

/**
 * Overlay-permission gate + start/stop toggle for the floating mic bubble.
 * If SYSTEM_ALERT_WINDOW is not granted, routes the user to system settings first.
 */
@Composable
private fun FloatingButtonControl() {
    val context = LocalContext.current
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var bubbleRunning by remember { mutableStateOf(VoiceOverlayService.isRunning) }

    val overlaySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
    }

    when {
        !overlayGranted -> {
            OutlinedButton(onClick = {
                overlaySettingsLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            }) { Text("Enable floating button") }
        }

        !bubbleRunning -> {
            Button(onClick = {
                VoiceOverlayService.start(context)
                bubbleRunning = true
            }) { Text("Start floating button") }
        }

        else -> {
            OutlinedButton(onClick = {
                VoiceOverlayService.stop(context)
                bubbleRunning = false
            }) { Text("Stop floating button") }
        }
    }
}
