package dev.dean.voice.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service for injecting cleaned transcription text into target apps.
 *
 * Strategy:
 *   1. Find the focused input field in the active window.
 *   2. Use ACTION_SET_TEXT to inject the cleaned text directly.
 *   3. If injection fails, fall back to clipboard + notification (VoiceClipboardFallback).
 *
 * Declared in AndroidManifest.xml with the accessibility_service_config.xml meta-data.
 *
 * Phase 5 implementation: wire up the full injection + fallback flow.
 *
 * TODO Phase 5: implement injectText(), onAccessibilityEvent(), fallback.
 */
class VoiceAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TODO Phase 5: track window state for app detection
    }

    override fun onInterrupt() {
        // Service was interrupted — no-op for now
    }

    /**
     * Inject [text] into the currently focused input field.
     *
     * @return true if injection succeeded; false if fallback is needed.
     *
     * TODO Phase 5: implement
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
