package dev.dean.voice.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Accessibility Service for injecting cleaned transcription text into target apps.
 *
 * Strategy:
 *   1. Find the focused input field in the active window.
 *   2. Use ACTION_SET_TEXT to inject the cleaned text directly.
 *   3. If injection fails, caller is responsible for clipboard fallback.
 *
 * Also tracks the foreground app package via [foregroundPackage] so the ViewModel
 * can record which app received the text.
 */
class VoiceAccessibilityService : AccessibilityService() {

    companion object {
        private val _instance = MutableStateFlow<VoiceAccessibilityService?>(null)

        /** Live reference to the running service, or null if not enabled. */
        val instance: StateFlow<VoiceAccessibilityService?> = _instance

        private val _foregroundPackage = MutableStateFlow<String?>(null)

        /** Package name of the currently foregrounded app. Updated on every window change. */
        val foregroundPackage: StateFlow<String?> = _foregroundPackage
    }

    override fun onServiceConnected() {
        _instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { pkg ->
                // Ignore our own app switching to foreground during delivery
                if (pkg != packageName) _foregroundPackage.value = pkg
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        _instance.value = null
        super.onDestroy()
    }

    /**
     * Inject [text] into the currently focused input field.
     *
     * @return true if injection succeeded; false if fallback is needed.
     */
    fun injectText(text: String): Boolean {
        val focusedNode: AccessibilityNodeInfo =
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return false

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
