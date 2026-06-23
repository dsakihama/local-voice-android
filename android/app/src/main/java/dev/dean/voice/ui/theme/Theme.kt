package dev.dean.voice.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Jarvis dark theme. Cyan signal on a near-black void; depth comes from glow and
 * hairline borders, not heavy shadows. There is no light variant by design.
 */
private val JarvisColorScheme = darkColorScheme(
    primary = Jarvis.Signal,
    onPrimary = Jarvis.Void,
    primaryContainer = Jarvis.SignalDim,
    onPrimaryContainer = Jarvis.Frost,

    secondary = Jarvis.SignalDeep,
    onSecondary = Jarvis.Void,

    tertiary = Jarvis.Core,
    onTertiary = Jarvis.Frost,

    background = Jarvis.Void,
    onBackground = Jarvis.Frost,

    surface = Jarvis.Deep,
    onSurface = Jarvis.Frost,
    surfaceVariant = Jarvis.Slate,
    onSurfaceVariant = Jarvis.Mist,

    // Sheets / menus / elevated containers
    surfaceContainerLowest = Jarvis.Void,
    surfaceContainerLow = Jarvis.Deep,
    surfaceContainer = Jarvis.Slate,
    surfaceContainerHigh = Jarvis.Slate2,
    surfaceContainerHighest = Jarvis.Slate2,

    outline = Jarvis.Line2,
    outlineVariant = Jarvis.Line,

    error = Jarvis.Crit,
    onError = Jarvis.Void,
    errorContainer = Jarvis.CritDim,
    onErrorContainer = Jarvis.Crit,
)

@Composable
fun LocalVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisColorScheme,
        content = content,
    )
}
