package dev.dean.voice.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Jarvis design-system color tokens (dark, moody blue; cyan is the one signal).
 * Mirrors design/_ds/.../tokens/colors.css. Cyan is reserved for the single most
 * important interactive element on any surface — use it sparingly.
 */
object Jarvis {
    // Base surfaces (dark canvas)
    val Void = Color(0xFF080C14)   // primary background
    val Deep = Color(0xFF0D1422)   // surface / panels
    val Slate = Color(0xFF111826)  // cards / containers
    val Slate2 = Color(0xFF161F30) // raised / hover surface

    // Text ramp (cool, frosted)
    val Frost = Color(0xFFE8EFF8)  // primary text / headings
    val Mist = Color(0xFF8A9BB5)   // body / secondary text
    val Muted = Color(0xFF4A5875)  // tertiary / disabled / meta

    // Accents
    val Signal = Color(0xFF00C8FF)     // primary accent · the one thing
    val SignalDeep = Color(0xFF00A6D6) // signal, pressed
    val Core = Color(0xFF0066FF)       // secondary accent · links

    // Borders & lines
    val Line = Color(0xFF1E2D45)   // default hairline border
    val Line2 = Color(0xFF283A57)  // stronger border / divider

    // Translucent signal fills
    val SignalDim = Color(0x2600C8FF)   // badge / tag fill (~0.15)
    val SignalFaint = Color(0x0F00C8FF) // active-row tint (~0.06)

    // Semantic status
    val Ok = Color(0xFF34D399)
    val Warn = Color(0xFFFBBF24)
    val Crit = Color(0xFFF87171)
    val CritDim = Color(0x24F87171)

    // Orb glow stops (radial, signal-cyan)
    val GlowStrong = Color(0x5500C8FF)
    val GlowMid = Color(0x2200C8FF)
    val GlowNone = Color(0x0000C8FF)
}
