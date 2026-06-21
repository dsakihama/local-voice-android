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
     * Returns the two parts the Prompt API expects:
     *   - [first]  = system/prefix instructions (maps to PromptPrefix)
     *   - [second] = user content to transform (maps to TextPart)
     *
     * Keeping them separate lets the caller set PromptPrefix explicitly so AICore
     * can cache/optimise the system instructions across requests.
     */
    fun buildParts(
        intent: VoiceIntent,
        rawText: String,
        examples: List<Pair<String, String>> = emptyList(),
    ): Pair<String, String> {
        val exampleBlock = buildExampleBlock(examples)
        val prefix = buildString {
            appendLine(systemPromptFor(intent))
            if (exampleBlock.isNotBlank()) {
                appendLine()
                appendLine("EXAMPLES OF YOUR PREVIOUS CLEANUPS:")
                append(exampleBlock)
            }
        }.trimEnd()

        val userContent = "Input:\n$rawText\n\nOutput (no introduction, no explanation):"
        return prefix to userContent
    }

    /** Convenience flat-string builder kept for tests / debugging. */
    fun build(
        intent: VoiceIntent,
        rawText: String,
        examples: List<Pair<String, String>> = emptyList(),
    ): String {
        val (prefix, user) = buildParts(intent, rawText, examples)
        return "$prefix\n\n$user"
    }

    private fun systemPromptFor(intent: VoiceIntent): String = when (intent) {
        VoiceIntent.AI_PROMPT,
        VoiceIntent.TEXT,
        VoiceIntent.EMAIL,
        VoiceIntent.NOTES -> """
            Add punctuation marks (. , ! ?) and capitalize the first word of each sentence only.
            Remove obvious filler words (umm, uh, um) only.
            Do not change, move, add, or remove any other words.
            Do not explain. Output only the corrected text.
        """.trimIndent()
    }

    private fun buildExampleBlock(examples: List<Pair<String, String>>): String {
        if (examples.isEmpty()) return ""
        return examples.take(10).joinToString("\n\n") { (raw, cleaned) ->
            "Raw: $raw\nCleaned: $cleaned"
        }
    }
}
