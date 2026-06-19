package dev.dean.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.dean.voice.ui.MainScreen
import dev.dean.voice.ui.theme.LocalVoiceTheme

/**
 * Single-activity entry point. Hosts the Compose UI tree.
 *
 * The FAB is a system overlay (SYSTEM_ALERT_WINDOW) and does not live here —
 * it is drawn by VoiceOverlayService. This activity handles settings,
 * onboarding, and permission requests.
 *
 * TODO (Phase 6): Launch overlay service from here once permissions are granted.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalVoiceTheme {
                MainScreen()
            }
        }
    }
}
