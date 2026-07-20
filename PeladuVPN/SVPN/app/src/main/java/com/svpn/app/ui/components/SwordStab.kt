package com.svpn.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.svpn.app.R

private const val STAB_DURATION_MS = 500

/**
 * The sword sits behind the connect button (draw it first / lower zIndex
 * in the parent Box) at a fixed angle, and doesn't rotate — instead it
 * slides further in when connected ("stabbed") and eases back out a bit
 * when disconnected, along its own blade axis.
 */
@Composable
fun SwordStab(
    phase: OuroborosPhase,
    onReturnedToRest: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 260.dp
) {
    // 0f = resting/pulled-out position, 1f = fully stabbed in.
    val stab = remember { Animatable(0f) }

    LaunchedEffect(phase) {
        val target = if (phase == OuroborosPhase.CONNECTED || phase == OuroborosPhase.CONNECTING) 1f else 0f
        stab.animateTo(
            targetValue = target,
            animationSpec = tween(durationMillis = STAB_DURATION_MS, easing = FastOutSlowInEasing)
        )
        if (phase == OuroborosPhase.DISCONNECTING) {
            onReturnedToRest()
        }
    }

    // Resting (disconnected) position.
    val baseOffsetXPx = 75f + 110f
    val baseOffsetYPx = with(LocalDensity.current) { (-18).dp.toPx() } - 71f - 152f
    // Stabbed-in (connected) position, exactly this far from resting.
    val stabDeltaXPx = -172f
    val stabDeltaYPx = 265f

    Image(
        painter = painterResource(id = R.drawable.sword),
        contentDescription = null,
        modifier = modifier
            .size(diameter)
            .graphicsLayer {
                rotationZ = 30f
                translationX = baseOffsetXPx + stabDeltaXPx * stab.value
                translationY = baseOffsetYPx + stabDeltaYPx * stab.value
            }
    )
}
