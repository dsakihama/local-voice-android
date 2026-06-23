package dev.dean.voice.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.dean.voice.ui.theme.Jarvis

/** Visual state of the central dictation orb. Maps from TranscribeViewModel.UiState. */
enum class OrbState { Idle, Listening, Processing, Disabled }

/**
 * The signature Jarvis orb: a luminous signal-cyan core with a breathing radial
 * glow. Cyan only while usable — [OrbState.Disabled] withdraws to muted slate
 * (never red). Listening intensifies the pulse and flips the glyph to a stop square.
 */
@Composable
fun VoiceOrb(
    state: OrbState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = state != OrbState.Disabled
    val transition = rememberInfiniteTransition(label = "orb")
    val pulseDur = if (state == OrbState.Listening) 900 else 1800

    val glowAlpha by transition.animateFloat(
        initialValue = if (state == OrbState.Listening) 0.45f else 0.30f,
        targetValue = if (state == OrbState.Listening) 0.95f else 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDur, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )
    val glowScale by transition.animateFloat(
        initialValue = 0.90f,
        targetValue = if (state == OrbState.Listening) 1.12f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDur, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowScale",
    )

    val coreBrush = if (active) {
        Brush.verticalGradient(listOf(Color(0xFF5FDCFF), Jarvis.Signal, Jarvis.SignalDeep))
    } else {
        Brush.verticalGradient(listOf(Jarvis.Slate2, Jarvis.Slate))
    }

    Box(modifier = modifier.size(180.dp), contentAlignment = Alignment.Center) {
        // Breathing glow halo
        Canvas(modifier = Modifier.size(180.dp)) {
            val radius = (size.minDimension / 2f) * (if (active) glowScale else 1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Jarvis.Signal.copy(alpha = if (active) glowAlpha * 0.5f else 0.05f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // Core
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(coreBrush)
                .clickable(enabled = active, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                OrbState.Processing -> CircularProgressIndicator(
                    color = Jarvis.Void,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(40.dp),
                )

                OrbState.Listening -> Icon(
                    Icons.Filled.Stop,
                    contentDescription = "Stop",
                    tint = Jarvis.Void,
                    modifier = Modifier.size(46.dp),
                )

                OrbState.Disabled -> Icon(
                    Icons.Filled.MicOff,
                    contentDescription = "Microphone unavailable",
                    tint = Jarvis.Mist,
                    modifier = Modifier.size(46.dp),
                )

                OrbState.Idle -> Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Tap to dictate",
                    tint = Jarvis.Void,
                    modifier = Modifier.size(46.dp),
                )
            }
        }
    }
}
