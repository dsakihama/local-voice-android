package dev.dean.voice

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dev.dean.voice.ui.MainScreen
import dev.dean.voice.ui.theme.LocalVoiceTheme

/**
 * Single-activity entry point. Hosts the Compose UI tree.
 *
 * The FAB is a system overlay (SYSTEM_ALERT_WINDOW) drawn by VoiceOverlayService,
 * not by this activity. Tapping the bubble relaunches this activity with
 * [EXTRA_AUTO_RECORD] so capture starts immediately; otherwise this activity
 * handles settings, onboarding, and permission requests.
 */
class MainActivity : ComponentActivity() {

    private val autoRecord = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        autoRecord.value = intent?.getBooleanExtra(EXTRA_AUTO_RECORD, false) == true
        setContent {
            LocalVoiceTheme {
                MainScreen(
                    autoRecord = autoRecord.value,
                    onAutoRecordConsumed = { autoRecord.value = false },
                )
            }
        }
    }

    /** The bubble launches us as a single task; pick up a fresh auto-record request. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_RECORD, false)) {
            autoRecord.value = true
        }
    }

    companion object {
        const val EXTRA_AUTO_RECORD = "dev.dean.voice.AUTO_RECORD"
    }
}
