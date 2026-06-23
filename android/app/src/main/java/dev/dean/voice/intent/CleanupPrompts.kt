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
        VoiceIntent.EMAIL -> """
            Add punctuation marks (. , ! ?) and capitalize the first word of each sentence only.
            Remove obvious filler words (umm, uh, um) only.
            Do not change, move, add, or remove any other words.
            Do not explain. Output only the corrected text.
        """.trimIndent()

        VoiceIntent.NOTES -> """
            Convert spoken dictation into clean markdown notes. Output raw markdown only — no code fences, no backticks, no explanation.

            DEFAULT: write clean prose with correct punctuation and paragraph breaks.
            Do NOT use checkboxes, bullets, numbers, or headings unless the speaker explicitly asks for that format.

            Apply a list/heading format ONLY when the speaker says so:
            - "checklist", "checkbox", "to-do", "check off" -> use "- [ ] " for each item
            - "bullet list", "bullets", "list of" -> use "- " for each item
            - "numbered list", "steps" -> use "1. " "2. " "3. " for each item
            - "section", "header", "heading" -> use "## " for that heading

            Example 1 — no formatting cue, so keep it as prose:
            Input: "umm so I talked to Greg today about the budget and he said we need to cut ten percent before Friday"
            Output:
            I talked to Greg today about the budget. He said we need to cut ten percent before Friday.

            Example 2 — explicit checklist cue:
            Input: "make a checklist run the team meeting consider what Morine needs and help Greg"
            Output:
            - [ ] Run the team meeting
            - [ ] Consider what Morine needs
            - [ ] Help Greg

            Remove filler words (um, uh, umm). Preserve all content. Output only the result.
        """.trimIndent()
    }

    private fun buildExampleBlock(examples: List<Pair<String, String>>): String {
        if (examples.isEmpty()) return ""
        return examples.take(10).joinToString("\n\n") { (raw, cleaned) ->
            "Raw: $raw\nCleaned: $cleaned"
        }
    }
}
