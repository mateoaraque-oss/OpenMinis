package com.openminis.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

/** Orion's mood, expressed in the vocabulary of the AMSY orb. */
fun OrionMood.toAmsyOrbState(): AmsyOrbState = when (this) {
    OrionMood.STANDBY -> AmsyOrbState.IDLE
    OrionMood.LISTENING -> AmsyOrbState.LISTENING
    OrionMood.THINKING -> AmsyOrbState.THINKING
    OrionMood.SPEAKING -> AmsyOrbState.SPEAKING
}

/**
 * Orion's orb: the AMSY particle sphere (see [AmsyOrbView]), animated from the
 * same signals Orion already publishes.
 *
 * The signature is unchanged on purpose, so every call site keeps working:
 * [levels] is the rolling 0..1 microphone buffer, [pulse] counts answer chunks.
 * Only the drawing is different — one cloud of 120 cyan particles that changes
 * shape with the state instead of a stack of decorative rings.
 *
 * @param mood current assistant state; drives the shape and the tempo.
 * @param levels rolling 0..1 microphone amplitudes (LISTENING).
 * @param pulse monotonically increasing counter; each new value is one answer
 *   chunk and kicks the orb, like a syllable.
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
    // Peak of the most recent samples, not the mean: a mean over a rolling
    // window lags a syllable behind and makes the orb feel disconnected from
    // the voice.
    val micLevel = remember(levels) {
        (levels.takeLast(8).maxOrNull() ?: 0f).coerceIn(0f, 1f)
    }

    // SPEAKING needs an envelope of its own: tokens arrive in bursts, and
    // snapping up and decaying gives the rings a syllable rhythm that a steady
    // spring cannot. Reset on every new chunk.
    val speech = remember { Animatable(0f) }
    LaunchedEffect(pulse, mood) {
        if (mood == OrionMood.SPEAKING) {
            if (pulse > 0) speech.snapTo(0.95f)
            speech.animateTo(0.35f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
        } else {
            speech.snapTo(0f)
        }
    }

    val targetLevel = when (mood) {
        OrionMood.LISTENING -> micLevel
        OrionMood.SPEAKING -> speech.value
        // A resting core, so the sphere never looks switched off.
        else -> 0.22f
    }
    val level by animateFloatAsState(
        targetValue = targetLevel,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 420f),
        label = "orionOrbLevel",
    )

    AmsyOrb(
        modifier = modifier,
        diameter = diameter,
        state = mood.toAmsyOrbState(),
        level = level,
    )
}
