package dev.dean.voice.intent

/**
 * LLM cleanup prompt templates, one per intent.
 *
 * Usage:
 *   val prompt = CleanupPrompts.build(intent, rawTranscript, examples)
 *
 * In-context examples (up to 10 recent training pairs) are injected between
 * the system prompt and the raw transcript to guide the model toward
 * the user's personal style without any weight updates.
 */
object CleanupPrompts {

    /**
     * Build a complete prompt for the given intent and raw transcript.
     *
     * @param intent       The selected VoiceIntent.
     * @param rawText      Raw STT output from Whisper.
     * @param examples     Recent (raw → cleaned) training pairs, most-recent first.
     *                     Injected as few-shot examples. Max ~10 pairs.
     */
    fun build(
        intent: VoiceIntent,
        rawText: String,
        examples: List<Pair<String, String>> = emptyList(),
    ): String {
        val systemPrompt = systemPromptFor(intent)
        val exampleBlock = buildExampleBlock(examples)
        return buildString {
            appendLine(systemPrompt)
            if (exampleBlock.isNotBlank()) {
                appendLine()
                appendLine("EXAMPLES OF YOUR PREVIOUS CLEANUPS:")
                appendLine(exampleBlock)
            }
            appendLine()
            appendLine("Clean this speech-to-text:")
            appendLine(rawText)
            appendLine()
            appendLine("Output: Only the cleaned text, nothing else.")
        }
    }

    private fun systemPromptFor(intent: VoiceIntent): String = when (intent) {
        VoiceIntent.AI_PROMPT -> """
            You are an AI prompt cleanup specialist. The user is sending instructions to an AI model.
            Make the prompt:
            - Clear and specific (remove filler words: umm, like, you know, uh)
            - Well-structured (use numbered steps if complex, markdown if needed)
            - Include necessary context and constraints
            - Preserve technical terms and jargon
            - Add formatting for readability (markdown, bullet points)
        """.trimIndent()

        VoiceIntent.TEXT -> """
            You are a casual messaging cleanup specialist.
            Keep the conversational tone. Fix typos and grammar.
            Remove filler words (umm, like, uh) but keep it sounding natural.
            Keep it short and punchy.
            Emoji-friendly: add emojis if they fit the vibe.
        """.trimIndent()

        VoiceIntent.EMAIL -> """
            You are a professional email cleanup specialist.
            Tone: polite, clear, direct.
            Structure: greeting → request/content → close.
            Remove filler words completely.
            Fix grammar strictly.
            Add appropriate punctuation and capitalization.
        """.trimIndent()

        VoiceIntent.NOTES -> """
            You are a note-taking cleanup specialist.
            Format as readable notes with clear structure.
            Use bullet points for lists.
            Preserve technical terms and jargon.
            Be concise and scannable.
        """.trimIndent()
    }

    private fun buildExampleBlock(examples: List<Pair<String, String>>): String {
        if (examples.isEmpty()) return ""
        return examples.take(10).joinToString("\n\n") { (raw, cleaned) ->
            "Raw: $raw\nCleaned: $cleaned"
        }
    }
}
