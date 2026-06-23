package dev.dean.voice.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.dean.voice.ui.theme.Jarvis
import kotlin.math.abs

/**
 * Stylized cyan waveform for the listening state (design s4). The ML Kit STT path
 * captures the mic internally and exposes no amplitude, so the bars are a smooth
 * decorative animation — a bell-curve envelope (tall center, short edges) with each
 * bar breathing on its own period. Reads as "listening", not a literal level meter.
 */
@Composable
fun ListeningWave(modifier: Modifier = Modifier, barCount: Int = 15) {
    val transition = rememberInfiniteTransition(label = "wave")
    val center = barCount / 2

    Row(
        modifier = modifier.height(120.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(barCount) { i ->
            val distance = abs(i - center).toFloat() / center // 0 at center, 1 at edges
            val maxFrac = lerp(1.0f, 0.28f, distance)         // tall center, short edges
            val minFrac = maxFrac * 0.32f
            val period = 560 + (i * 47) % 420                 // varied, organic

            val frac by transition.animateFloat(
                initialValue = minFrac,
                targetValue = maxFrac,
                animationSpec = infiniteRepeatable(
                    animation = tween(period, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$i",
            )

            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight(frac)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.verticalGradient(listOf(Jarvis.Signal, Jarvis.SignalDeep))),
            )
        }
    }
}
