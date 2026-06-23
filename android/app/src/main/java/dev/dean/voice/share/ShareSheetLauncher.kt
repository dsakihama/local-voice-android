package dev.dean.voice.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.dean.voice.apps.TargetAppRegistry
import dev.dean.voice.intent.VoiceIntent

/**
 * Hands cleaned text to Android's native share sheet, shaping the targets per
 * intent ("meaningful intents"). Implements `design/share-text-requirements.md`:
 *
 *  - R2/R3: every path is an `ACTION_SEND` `text/plain` wrapped in `createChooser`.
 *  - R6:    AI intent restricts the sheet to installed AI apps (allowlist).
 *  - R5:    Notes pins a deterministic `obsidian://new` entry when Obsidian is installed.
 *  - Email/Text pin the most relevant installed apps via `EXTRA_INITIAL_INTENTS`.
 *
 * Package lists come from [TargetAppRegistry] so there is a single source of truth.
 */
object ShareSheetLauncher {

    private const val OBSIDIAN_PKG = "md.obsidian"

    fun share(context: Context, intent: VoiceIntent, text: String) {
        when (intent) {
            VoiceIntent.AI_PROMPT -> shareToAi(context, text)
            VoiceIntent.NOTES     -> shareToNotes(context, text)
            VoiceIntent.EMAIL     -> shareToEmail(context, text)
            VoiceIntent.TEXT      -> shareGeneral(context, text, "Share to…", pinned(context, TargetAppRegistry.Category.COMMS, text))
        }
    }

    // ── Base builders ─────────────────────────────────────────────────────────

    private fun baseSend(text: String, subject: String? = null): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let {
                putExtra(Intent.EXTRA_SUBJECT, it)
                putExtra(Intent.EXTRA_TITLE, it)
            }
        }

    /** Package-restricted copies of [base] for each installed app in [category]. */
    private fun pinned(
        context: Context,
        category: TargetAppRegistry.Category,
        text: String,
        subject: String? = null,
    ): List<Intent> = TargetAppRegistry.installedApps(context, category).map { app ->
        Intent(baseSend(text, subject)).setPackage(app.id)
    }

    /** Wrap [base] in a chooser, pin [initialIntents] on top, and launch. */
    private fun launchChooser(
        context: Context,
        base: Intent,
        title: String,
        initialIntents: List<Intent> = emptyList(),
    ) {
        val chooser = Intent.createChooser(base, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (initialIntents.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
            }
        }
        context.startActivity(chooser)
    }

    private fun shareGeneral(context: Context, text: String, title: String, initial: List<Intent>) {
        launchChooser(context, baseSend(text), title, initial)
    }

    // ── Per-intent variants ───────────────────────────────────────────────────

    /** R6 — restrict the sheet to installed AI apps; fall back to a general sheet if none. */
    private fun shareToAi(context: Context, text: String) {
        val aiIntents = pinned(context, TargetAppRegistry.Category.AI, text)
        if (aiIntents.isEmpty()) {
            shareGeneral(context, text, "Share to…", emptyList())
            return
        }
        // Restricting the chooser's base to AI packages keeps non-AI apps out of the sheet.
        launchChooser(
            context,
            base = aiIntents.first(),
            title = "Share to AI…",
            initialIntents = aiIntents.drop(1),
        )
    }

    /**
     * R5 — pin a deterministic new-note entry for Obsidian; plain SEND remains as fallback.
     *
     * The text arrives with a "# <timestamp>" heading prepended by the ViewModel.
     * To avoid the timestamp appearing twice:
     *  - the `obsidian://new` entry carries the timestamp as the note **name** (filename/title)
     *    and a body with that heading stripped;
     *  - the plain SEND paths keep the heading in the body and set **no** EXTRA_SUBJECT,
     *    so receivers don't surface the timestamp a second time as a title.
     */
    private fun shareToNotes(context: Context, text: String) {
        val title = noteTitle(text)              // timestamp, from the "# …" first line
        val initial = mutableListOf<Intent>()
        if (TargetAppRegistry.isInstalled(context, OBSIDIAN_PKG)) {
            initial += obsidianNewNote(title, stripLeadingHeading(text))
        }
        initial += pinned(context, TargetAppRegistry.Category.PKB, text)
        launchChooser(context, baseSend(text), "Save note…", initial.distinctBy { it.`package` to it.data })
    }

    private fun shareToEmail(context: Context, text: String) {
        val subject = emailSubject(text)
        launchChooser(
            context,
            base = baseSend(text, subject),
            title = "Send email…",
            initialIntents = pinned(context, TargetAppRegistry.Category.COMMS, text, subject),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * `obsidian://new` deep link wrapped in ACTION_VIEW. Every value is URI-encoded
     * (reserved `/` and `#` in the body would otherwise break parsing). No vault is
     * specified — the last-focused vault is used; see the cold-start quirk in the
     * requirements doc, for which the plain SEND path is the fallback.
     */
    private fun obsidianNewNote(title: String, body: String): Intent {
        val uri = "obsidian://new" +
            "?name=" + Uri.encode(title) +
            "&content=" + Uri.encode(body)
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri))
    }

    /**
     * Note title = the first line of the text. The ViewModel prepends a
     * "# <date time>" heading for Notes, so the Obsidian note name and the
     * share subject become the timestamp. Strips the markdown heading marker.
     */
    private fun noteTitle(text: String): String {
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        return firstLine.removePrefix("#").trim().ifBlank { "Voice note" }
    }

    /** Drop a leading "# …" heading line (and the blank line after it) — the Obsidian
     *  note name already carries the timestamp, so the body shouldn't repeat it. */
    private fun stripLeadingHeading(text: String): String {
        val lines = text.lines()
        if (lines.firstOrNull()?.trimStart()?.startsWith("#") != true) return text
        var rest = lines.drop(1)
        if (rest.firstOrNull()?.isBlank() == true) rest = rest.drop(1)
        return rest.joinToString("\n")
    }

    private fun emailSubject(text: String): String =
        firstWords(text, 6).ifBlank { "Voice note" }

    private fun firstWords(text: String, n: Int): String =
        text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(n).joinToString(" ")
}
