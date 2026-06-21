package dev.dean.voice.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dean.voice.intent.VoiceIntent

@Composable
fun MainScreen(vm: TranscribeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val selectedIntent by vm.intent.collectAsState()

    LaunchedEffect(Unit) { vm.warmupPromptModel() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startRecording()
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
            Spacer(Modifier.height(32.dp))

            Text("Local Voice", style = MaterialTheme.typography.headlineMedium)
            Text(
                "ML Kit STT + MediaPipe Gemma 3 1B",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            // Intent selector — only interactive when idle
            val intentEnabled = state is TranscribeViewModel.UiState.Idle
            IntentSelector(
                selected = selectedIntent,
                enabled = intentEnabled,
                onSelect = vm::setIntent,
            )

            Spacer(Modifier.height(32.dp))

            when (val s = state) {
                is TranscribeViewModel.UiState.Idle -> {
                    Button(onClick = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) {
                        Text("Record")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Select an intent, then tap Record. Watch Logcat tags SttProbe and MediaPipeLlmCleanup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                is TranscribeViewModel.UiState.CheckingAvailability -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Checking AICore availability…")
                }

                is TranscribeViewModel.UiState.Downloading -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Downloading Gemini Nano model… (first run only)")
                }

                is TranscribeViewModel.UiState.Recording -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Text("Listening…")
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { vm.stopRecording() }) {
                        Text("Stop")
                    }
                }

                is TranscribeViewModel.UiState.Partial -> {
                    Text(
                        text = s.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { vm.stopRecording() }) {
                        Text("Stop")
                    }
                }

                is TranscribeViewModel.UiState.Cleaning -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(16.dp))
                    Text("Gemma 3 1B cleaning…", color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(16.dp))
                    ComparisonCard(
                        label = "Raw STT",
                        text = s.rawText,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                is TranscribeViewModel.UiState.Result -> {
                    // Quality comparison panel — the core prototype deliverable
                    Text(
                        "Cleanup result · ${s.latencyMs}ms · ${s.intent.displayName}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(16.dp))

                    ComparisonCard(
                        label = "Raw STT",
                        text = s.rawText,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    ComparisonCard(
                        label = "Cleaned (Gemma 3 1B)",
                        text = s.cleanedText,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    )

                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { vm.reset() }) {
                        Text("Record Again")
                    }
                }

                is TranscribeViewModel.UiState.Error -> {
                    Text(
                        text = "Error: ${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { vm.reset() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text("Try Again")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun IntentSelector(
    selected: VoiceIntent,
    enabled: Boolean,
    onSelect: (VoiceIntent) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Intent",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VoiceIntent.entries.forEach { intent ->
                FilterChip(
                    selected = intent == selected,
                    enabled = enabled,
                    onClick = { onSelect(intent) },
                    label = { Text(intent.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
private fun ComparisonCard(
    label: String,
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}
