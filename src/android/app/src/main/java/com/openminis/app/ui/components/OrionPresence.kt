package com.openminis.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R

/**
 * [T-orion-presence] The orb's public face, docked under the chat's top bar.
 *
 * The big orb inside the voice panel is the immersive surface: it appears
 * when the user actually talks to Orion. This strip is the persistent one.
 * It shows up for every turn — typed or spoken — so the assistant is never
 * invisible while it works, and it is out of the message flow, so it can
 * never cover an answer.
 *
 * It renders nothing in STANDBY: an assistant that is idle should not take
 * up space or attention.
 */
@Composable
fun OrionPresenceStrip(
    listening: Boolean,
    streaming: Boolean,
    answering: Boolean,
    levels: List<Float>,
    pulseKey: Int,
    modifier: Modifier = Modifier,
) {
    val mood = when {
        listening -> OrionMood.LISTENING
        answering -> OrionMood.SPEAKING
        streaming -> OrionMood.THINKING
        else -> OrionMood.STANDBY
    }
    val visible = mood != OrionMood.STANDBY

    // One short tick when the mic opens: enough to feel the assistant wake
    // up, not enough to buzz on every turn.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(mood) {
        if (mood == OrionMood.LISTENING) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val label = when (mood) {
        OrionMood.LISTENING -> stringResource(R.string.orion_state_listening)
        OrionMood.THINKING -> stringResource(R.string.orion_state_thinking)
        OrionMood.SPEAKING -> stringResource(R.string.orion_state_speaking)
        OrionMood.STANDBY -> ""
    }

    val dim = when (mood) {
        OrionMood.LISTENING -> Color(0xFF3FD4FF)
        OrionMood.THINKING -> Color(0xFF9B6BFF)
        OrionMood.SPEAKING -> Color(0xFFFFC46B)
        OrionMood.STANDBY -> Color.Transparent
    }

    // The label breathes at the assistant's tempo, so even the text agrees
    // with the orb instead of sitting there static.
    val labelTick = rememberInfiniteTransition(label = "orionPresenceText")
    val labelAlpha by labelTick.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (mood) {
                    OrionMood.THINKING -> 900
                    OrionMood.SPEAKING -> 1200
                    else -> 1600
                },
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orionPresenceAlpha",
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(260)) + shrinkVertically(tween(260)),
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrionOrb(
                levels = levels,
                isActive = mood == OrionMood.LISTENING,
                mood = mood,
                pulse = pulseKey,
                diameter = 26.dp,
            )
            Text(
                text = label,
                modifier = Modifier.padding(start = 10.dp),
                color = dim.copy(alpha = labelAlpha),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
