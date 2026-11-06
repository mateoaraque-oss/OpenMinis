package com.openminis.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Orion's orb.
 *
 * A living sphere that reacts to the user's voice: [levels] is the same
 * rolling 0..1 amplitude buffer SpeechRecognitionManager already publishes
 * for the waveform, so the orb breathes with the microphone and not with a
 * decorative random animation.
 *
 * Three visual layers, back to front:
 *  - an outer glow whose radius and opacity track the voice level;
 *  - the core sphere, a radial gradient lit from the upper-left so it reads
 *    as a body rather than a flat circle;
 *  - orbiting arcs and particles that rotate on their own clock, which keeps
 *    the thing alive while idle (silence should still look attentive).
 *
 * Deliberately dependency-free: one Canvas, two infinite animations, one
 * spring for the amplitude. No images, no shaders, nothing that needs a
 * GPU feature a budget SoC might refuse.
 */
@Composable
fun OrionOrb(
    levels: List<Float>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 112.dp,
    accent: Color = Color(0xFF7C6CFF),
    accentAlt: Color = Color(0xFF00D9C0),
) {
    // Peak of the most recent samples, not the mean: a mean over a rolling
    // window lags a syllable behind and makes the orb feel disconnected from
    // the voice.
    val target = if (isActive) {
        (levels.takeLast(8).maxOrNull() ?: 0f).coerceIn(0f, 1f)
    } else {
        0f
    }
    val amplitude by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 340f),
        label = "orionAmplitude",
    )

    val transition = rememberInfiniteTransition(label = "orionOrb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orionSpin",
    )
    val counterSpin by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orionCounterSpin",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.955f,
        targetValue = 1.045f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orionBreathe",
    )
    val shimmer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orionShimmer",
    )

    Canvas(modifier = modifier.then(Modifier)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val half = size.minDimension / 2f

        // 1. Outer glow. Grows with the voice; stays faintly present when idle
        //    so the orb never looks switched off.
        val glowRadius = half * (0.92f + 0.20f * amplitude)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.30f + 0.34f * amplitude),
                    accentAlt.copy(alpha = 0.10f + 0.14f * amplitude),
                    Color.Transparent,
                ),
                center = center,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = center,
        )

        // 2. Orbiting rings. They read as motion even in silence.
        for (i in 0 until 3) {
            val ringRadius = half * (0.62f + 0.11f * i) * breathe
            val start = if (i % 2 == 0) spin + i * 60f else counterSpin + i * 45f
            drawArc(
                color = (if (i % 2 == 0) accent else accentAlt)
                    .copy(alpha = 0.26f - i * 0.06f),
                startAngle = start,
                sweepAngle = 118f - i * 16f,
                useCenter = false,
                topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                size = Size(ringRadius * 2f, ringRadius * 2f),
                style = Stroke(width = (2.4f - i * 0.5f).coerceAtLeast(0.9f) * density),
            )
        }

        // 3. Core sphere, lit from the upper left.
        val coreRadius = half * (0.44f + 0.075f * amplitude) * breathe
        val highlight = Offset(
            center.x - coreRadius * 0.32f,
            center.y - coreRadius * 0.38f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.97f),
                    accent.copy(alpha = 0.92f),
                    Color(0xFF2A1B63),
                    Color(0xFF0E0A24),
                ),
                center = highlight,
                radius = coreRadius * 1.65f,
            ),
            radius = coreRadius,
            center = center,
        )

        // 4. Rim light: a thin bright edge on the lower right sells the
        //    sphere as a solid volume.
        drawArc(
            color = accentAlt.copy(alpha = 0.55f + 0.25f * amplitude),
            startAngle = 20f + spin * 0.15f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - coreRadius, center.y - coreRadius),
            size = Size(coreRadius * 2f, coreRadius * 2f),
            style = Stroke(width = 1.6f * density),
        )

        // 5. Particles: a small constellation that drifts on the outer band
        //    and brightens with the voice.
        val particleCount = 7
        for (i in 0 until particleCount) {
            val phase = (i.toFloat() / particleCount) * 2f * PI.toFloat()
            val angle = phase + (spin * 0.6f) * (PI.toFloat() / 180f)
            val orbit = half * (0.78f + 0.06f * sin(phase * 2f + shimmer * 2f * PI.toFloat()))
            val px = center.x + cos(angle) * orbit
            val py = center.y + sin(angle) * orbit
            val pr = (1.6f + 2.2f * amplitude) * (0.7f + 0.3f * sin(phase * 3f)) * density
            drawCircle(
                color = if (i % 2 == 0) accent.copy(alpha = 0.75f) else accentAlt.copy(alpha = 0.75f),
                radius = pr,
                center = Offset(px, py),
            )
        }

        // 6. A soft inner spark at the very centre, pulsing with the voice.
        drawCircle(
            color = Color.White.copy(alpha = 0.18f + 0.35f * amplitude),
            radius = coreRadius * (0.18f + 0.10f * amplitude),
            center = center,
        )
    }
}
