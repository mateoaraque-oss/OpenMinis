package com.openminis.app.ui.components

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * What Orion is doing right now. The orb is not decoration: each state is a
 * different body language, so the user can tell at a glance whether the
 * assistant is waiting, hearing them, thinking, or answering.
 */
enum class OrionMood {
    /** Nobody is talking. Slow breathing, a dim drift, occasional spark. */
    STANDBY,

    /** The microphone is live and the orb is following the user's voice. */
    LISTENING,

    /** A request is in flight and no token has arrived yet. */
    THINKING,

    /** Tokens are landing. The orb pulses once per chunk, like syllables. */
    SPEAKING,
}

/**
 * Orion's orb.
 *
 * A living sphere that reacts to the user's voice: [levels] is the same
 * rolling 0..1 amplitude buffer SpeechRecognitionManager already publishes
 * for the waveform, so the orb breathes with the microphone and not with a
 * decorative random animation.
 *
 * The four layers, back to front:
 *  - an outer glow whose radius and opacity track the voice level;
 *  - orbiting arcs and a particle constellation, each state with its own
 *    tempo (standby drifts, thinking spins hard, speaking snaps);
 *  - the core sphere, a radial gradient lit from the upper-left so it reads
 *    as a body rather than a flat circle;
 *  - the rim light and a centre spark that flares on each voice peak.
 *
 * Deliberately dependency-free: one Canvas, a handful of animations. No
 * images, no shaders, nothing that needs a GPU feature a budget SoC might
 * refuse. Every animation is driven by plain floats, and every float is
 * either the voice or a clock, so the thing never lies about what the app
 * is doing.
 *
 * @param mood current assistant state; drives palette, tempo and motion.
 * @param levels rolling 0..1 microphone amplitudes (LISTENING).
 * @param pulse monotonically increasing counter; each new value is one
 *   answer chunk and fires a spring-loaded pulse (SPEAKING).
 */
@Composable
fun OrionOrb(
    levels: List<Float>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 112.dp,
    mood: OrionMood = if (isActive) OrionMood.LISTENING else OrionMood.STANDBY,
    pulse: Int = 0,
) {
    val palette = mood.palette()
    val accent = palette.accent
    val accentAlt = palette.alt

    // Peak of the most recent samples, not the mean: a mean over a rolling
    // window lags a syllable behind and makes the orb feel disconnected from
    // the voice.
    val micTarget = if (mood == OrionMood.LISTENING) {
        (levels.takeLast(8).maxOrNull() ?: 0f).coerceIn(0f, 1f)
    } else {
        0f
    }

    // SPEAKING needs an envelope of its own: tokens arrive in bursts, and
    // snapping to 1 and decaying gives the orb a syllable rhythm that a
    // steady spring cannot. Reset on every new chunk.
    val speech = remember { Animatable(0f) }
    LaunchedEffect(pulse, mood) {
        if (mood == OrionMood.SPEAKING && pulse > 0) {
            speech.snapTo(1f)
            speech.animateTo(0f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        } else if (mood != OrionMood.SPEAKING) {
            speech.animateTo(0f, tween(durationMillis = 200))
        }
    }

    val amplitude by animateFloatAsState(
        targetValue = if (mood == OrionMood.LISTENING) micTarget else speech.value,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "orionAmplitude",
    )

    // Tempo per state. Standby is a sleeping animal; thinking is a fan.
    val spinMillis = when (mood) {
        OrionMood.STANDBY -> 21000
        OrionMood.LISTENING -> 9000
        OrionMood.THINKING -> 2400
        OrionMood.SPEAKING -> 6200
    }
    val breatheMillis = when (mood) {
        OrionMood.STANDBY -> 5200
        OrionMood.LISTENING -> 2400
        OrionMood.THINKING -> 1300
        OrionMood.SPEAKING -> 2200
    }
    val breatheScale = when (mood) {
        OrionMood.STANDBY -> 0.030f
        OrionMood.LISTENING -> 0.045f
        OrionMood.THINKING -> 0.020f
        OrionMood.SPEAKING -> 0.055f
    }

    val transition = rememberInfiniteTransition(label = "orionOrb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = spinMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orionSpin",
    )
    // Counter-rotation: two rings turning opposite ways read as depth, which
    // a single rotating sprite never does.
    val counterSpin by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (spinMillis * 1.55).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orionCounterSpin",
    )
    val breathe by transition.animateFloat(
        initialValue = 1f - breatheScale,
        targetValue = 1f + breatheScale,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = breatheMillis, easing = FastOutSlowInEasing),
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
    // THINKING draws an extra scanning arc; reusing `spin` would tie it to the
    // rings, so give the sweep its own clock.
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orionSweep",
    )

    Canvas(modifier = modifier.size(diameter)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val half = size.minDimension / 2f
        val stateBoost = when (mood) {
            OrionMood.STANDBY -> 0f
            OrionMood.LISTENING -> 0.18f
            OrionMood.THINKING -> 0.10f
            OrionMood.SPEAKING -> 0.14f
        }

        // 1. Outer glow. Grows with the voice; stays faintly present when idle
        //    so the orb never looks switched off.
        val glowRadius = half * (0.90f + 0.20f * amplitude + stateBoost * 0.5f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.26f + 0.34f * amplitude + stateBoost * 0.5f),
                    accentAlt.copy(alpha = 0.08f + 0.14f * amplitude),
                    Color.Transparent,
                ),
                center = center,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = center,
        )

        // 2. A sonar ring on LISTENING: a circle that keeps expanding and
        //    fading, so silence still shows the mic is open and waiting.
        if (mood == OrionMood.LISTENING) {
            val sonar = (shimmer * 1.6f) % 1f
            val sonarRadius = half * (0.55f + 0.55f * sonar)
            drawCircle(
                color = accentAlt.copy(alpha = 0.24f * (1f - sonar)),
                radius = sonarRadius,
                center = center,
                style = Stroke(width = 1.6f * density),
            )
        }

        // 3. Orbiting rings. They read as motion even in silence. THINKING
        //    gets a longer, faster sweep arc on top.
        for (i in 0 until 3) {
            val ringRadius = half * (0.60f + 0.115f * i) * breathe
            val start = if (i % 2 == 0) spin + i * 60f else counterSpin + i * 45f
            drawArc(
                color = (if (i % 2 == 0) accent else accentAlt)
                    .copy(alpha = 0.30f - i * 0.07f),
                startAngle = start,
                sweepAngle = 118f - i * 16f,
                useCenter = false,
                topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                size = Size(ringRadius * 2f, ringRadius * 2f),
                style = Stroke(width = (2.6f - i * 0.5f).coerceAtLeast(0.9f) * density),
            )
        }
        if (mood == OrionMood.THINKING) {
            val r = half * 0.74f * breathe
            drawArc(
                color = accent.copy(alpha = 0.70f),
                startAngle = sweep,
                sweepAngle = 64f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = 2.2f * density),
            )
            // Two satellites chasing the sweep, like a radar lock.
            for (k in 0 until 2) {
                val a = ((sweep + k * 180f) * PI.toFloat() / 180f)
                drawCircle(
                    color = accentAlt.copy(alpha = 0.85f),
                    radius = 2.4f * density,
                    center = Offset(center.x + cos(a) * r, center.y + sin(a) * r),
                )
            }
        }

        // 4. Core sphere, lit from the upper left.
        val coreRadius = half * (0.43f + 0.075f * amplitude + stateBoost * 0.10f) * breathe
        val highlight = Offset(
            center.x - coreRadius * 0.32f,
            center.y - coreRadius * 0.38f,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.97f),
                    accent.copy(alpha = 0.94f),
                    palette.coreMid,
                    palette.coreDark,
                ),
                center = highlight,
                radius = coreRadius * 1.65f,
            ),
            radius = coreRadius,
            center = center,
        )

        // 5. Rim light: a thin bright edge on the lower right sells the
        //    sphere as a solid volume.
        drawArc(
            color = accentAlt.copy(alpha = 0.50f + 0.30f * amplitude),
            startAngle = 20f + spin * 0.15f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - coreRadius, center.y - coreRadius),
            size = Size(coreRadius * 2f, coreRadius * 2f),
            style = Stroke(width = 1.6f * density),
        )

        // 6. Particles: a constellation that drifts on the outer band and
        //    brightens with the voice. Thinking pulls them into a tighter,
        //    faster orbit; standby lets them spread and crawl.
        val particleCount = if (mood == OrionMood.THINKING) 10 else 7
        val orbitBase = when (mood) {
            OrionMood.STANDBY -> 0.80f
            OrionMood.THINKING -> 0.66f
            else -> 0.78f
        }
        val drift = when (mood) {
            OrionMood.STANDBY -> 0.35f
            OrionMood.LISTENING -> 0.65f
            OrionMood.THINKING -> 1.6f
            OrionMood.SPEAKING -> 0.9f
        }
        for (i in 0 until particleCount) {
            val phase = (i.toFloat() / particleCount) * 2f * PI.toFloat()
            val angle = phase + (spin * drift) * (PI.toFloat() / 180f)
            val orbit = half * (orbitBase + 0.06f * sin(phase * 2f + shimmer * 2f * PI.toFloat()))
            val px = center.x + cos(angle) * orbit
            val py = center.y + sin(angle) * orbit
            val pr = (1.5f + 2.4f * amplitude) * (0.7f + 0.3f * sin(phase * 3f)) * density
            drawCircle(
                color = if (i % 2 == 0) accent.copy(alpha = 0.75f) else accentAlt.copy(alpha = 0.75f),
                radius = pr,
                center = Offset(px, py),
            )
        }

        // 7. A soft inner spark at the very centre, pulsing with the voice.
        drawCircle(
            color = Color.White.copy(alpha = 0.16f + 0.38f * amplitude),
            radius = coreRadius * (0.17f + 0.11f * amplitude),
            center = center,
        )

        // 8. STANDBY gets a rare "shooting star": one thin arc that crosses
        //    the rim every few seconds. Cheap, and it makes idle feel alive
        //    instead of looped.
        if (mood == OrionMood.STANDBY) {
            val cycle = (shimmer * 3f) % 1f
            if (cycle < 0.22f) {
                val t = cycle / 0.22f
                val r = half * 0.86f
                drawArc(
                    color = accentAlt.copy(alpha = 0.55f * (1f - t)),
                    startAngle = 200f + t * 90f,
                    sweepAngle = 26f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = 1.4f * density),
                )
            }
        }
    }
}

/** Colour identity per state: cool when idle, cyan when listening, violet
 *  when thinking, warm gold when speaking. The shift is what makes the
 *  state readable from across the room. */
private data class OrbPalette(
    val accent: Color,
    val alt: Color,
    val coreMid: Color,
    val coreDark: Color,
)

private fun OrionMood.palette(): OrbPalette = when (this) {
    OrionMood.STANDBY -> OrbPalette(
        accent = Color(0xFF6E5BFF),
        alt = Color(0xFF00D9C0),
        coreMid = Color(0xFF2A1B63),
        coreDark = Color(0xFF0B0819),
    )
    OrionMood.LISTENING -> OrbPalette(
        accent = Color(0xFF3FD4FF),
        alt = Color(0xFF00FFC2),
        coreMid = Color(0xFF0E3A63),
        coreDark = Color(0xFF05131F),
    )
    OrionMood.THINKING -> OrbPalette(
        accent = Color(0xFF9B6BFF),
        alt = Color(0xFFE45BFF),
        coreMid = Color(0xFF3A1668),
        coreDark = Color(0xFF120723),
    )
    OrionMood.SPEAKING -> OrbPalette(
        accent = Color(0xFFFFC46B),
        alt = Color(0xFF7C6CFF),
        coreMid = Color(0xFF5A3410),
        coreDark = Color(0xFF1A0E05),
    )
}
