package dev.dean.voice.apps

import android.content.Context
import android.content.pm.PackageManager

/**
 * Registry of known target apps, grouped by category.
 * Filters to only apps actually installed on the device.
 *
 * Phase 5 will add frequency ranking on top of this base list.
 */
object TargetAppRegistry {

    data class TargetApp(
        val id: String,          // Package name — used as stable ID in the DB
        val displayName: String,
        val category: Category,
        val webFallbackUrl: String? = null,  // If no native app, open this URL
    )

    enum class Category(val displayName: String) {
        AI("AI Apps"),
        COMMS("Comms"),
        PKB("Notes & PKB"),
    }

    /** All known apps — full list regardless of installation status. */
    private val ALL_APPS = listOf(
        // AI
        TargetApp("com.anthropic.claude",     "Claude",      Category.AI, "https://claude.ai"),
        TargetApp("com.openai.chatgpt",       "ChatGPT",     Category.AI, "https://chat.openai.com"),
        TargetApp("ai.perplexity.app",        "Perplexity",  Category.AI, "https://perplexity.ai"),

        // Comms
        TargetApp("com.android.messaging",    "SMS",         Category.COMMS),
        TargetApp("com.google.android.gm",    "Gmail",       Category.COMMS),
        TargetApp("com.microsoft.office.outlook", "Outlook", Category.COMMS),
        TargetApp("com.whatsapp",             "WhatsApp",    Category.COMMS),
        TargetApp("com.Slack",                "Slack",       Category.COMMS),
        TargetApp("com.discord",              "Discord",     Category.COMMS),
        TargetApp("org.telegram.messenger",   "Telegram",    Category.COMMS),

        // PKB
        TargetApp("md.obsidian",              "Obsidian",    Category.PKB),
        TargetApp("notion.id",                "Notion",      Category.PKB, "https://notion.so"),
        TargetApp("com.microsoft.onenote",    "OneNote",     Category.PKB),
    )

    /**
     * Returns apps that are installed on this device, grouped by category.
     * Apps with a webFallbackUrl are always included even if the native app is absent.
     */
    fun getInstalledApps(context: Context): Map<Category, List<TargetApp>> {
        val pm: PackageManager = context.packageManager
        return ALL_APPS
            .filter { app ->
                app.webFallbackUrl != null || isInstalled(pm, app.id)
            }
            .groupBy { it.category }
    }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
