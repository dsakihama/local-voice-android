package dev.dean.voice.intent

/**
 * The four intent types the user selects before dictating.
 * Each maps to a distinct LLM cleanup prompt style.
 */
enum class VoiceIntent(
    val displayName: String,
    val description: String,
) {
    AI_PROMPT(
        displayName = "AI Prompt",
        description = "Structured, clear, context-rich",
    ),
    TEXT(
        displayName = "Text",
        description = "Natural, conversational, concise",
    ),
    EMAIL(
        displayName = "Email",
        description = "Professional, structured, clear",
    ),
    NOTES(
        displayName = "Notes",
        description = "Scannable, bullet-point structure",
    );

    companion object {
        fun fromString(value: String): VoiceIntent =
            entries.firstOrNull { it.name == value } ?: AI_PROMPT
    }
}
