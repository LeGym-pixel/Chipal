package com.svpn.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.svpn.app.R
import kotlinx.coroutines.delay

/** Mirrors the VPN lifecycle, but purely for driving the ring's rotation/audio. */
enum class OuroborosPhase { STOPPED, CONNECTING, CONNECTED, DISCONNECTING }

enum class RingDirection { CLOCKWISE, COUNTER_CLOCKWISE }

private const val RETURN_DURATION_MS = 900

/**
 * A rotating image tied to the VPN lifecycle. Generic enough to cover every
 * ring-style button design (Ouroboros, Chains, Sun, Double Sun) — they only
 * differ in image, speed, direction, and whether disconnecting eases back
 * to a clean 0° rest position or just stops wherever it happens to be.
 */
@Composable
fun RotatingRingImage(
    phase: OuroborosPhase,
    onReturnedToRest: () -> Unit,
    drawableRes: Int,
    fastDegreesPerSec: Float,
    slowDegreesPerSec: Float,
    direction: RingDirection,
    returnToRestOnDisconnect: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 260.dp
) {
    val angle = remember { Animatable(0f) }
    val sign = if (direction == RingDirection.CLOCKWISE) 1f else -1f

    LaunchedEffect(phase) {
        when (phase) {
            OuroborosPhase.STOPPED -> {
                // No animation; stays wherever it is.
            }
            OuroborosPhase.CONNECTING -> {
                while (true) {
                    angle.snapTo(angle.value + sign * fastDegreesPerSec / 60f)
                    delay(16L)
                }
            }
            OuroborosPhase.CONNECTED -> {
                while (true) {
                    angle.snapTo(angle.value + sign * slowDegreesPerSec / 60f)
                    delay(16L)
                }
            }
            OuroborosPhase.DISCONNECTING -> {
                if (returnToRestOnDisconnect) {
                    // Ease smoothly forward (same direction) to the next
                    // multiple of 360°, then snap to a clean 0 rest state.
                    val current = angle.value
                    val nextMultiple = (Math.ceil((current / 360f).toDouble()) * 360f).toFloat()
                    val target = if (kotlin.math.abs(nextMultiple - current) < 1f) nextMultiple + 360f else nextMultiple
                    angle.animateTo(
                        targetValue = target,
                        animationSpec = tween(durationMillis = RETURN_DURATION_MS, easing = LinearEasing)
                    )
                    angle.snapTo(0f)
                } else {
                    // Just stop spinning exactly where it currently is —
                    // no return-to-rest animation, no snap to 0.
                }
                onReturnedToRest()
            }
        }
    }

    Image(
        painter = painterResource(id = drawableRes),
        contentDescription = null,
        modifier = modifier
            .size(diameter)
            .graphicsLayer { rotationZ = angle.value }
    )
}

@Composable
fun OuroborosRing(
    phase: OuroborosPhase,
    onReturnedToRest: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 260.dp
) = RotatingRingImage(
    phase = phase,
    onReturnedToRest = onReturnedToRest,
    drawableRes = R.drawable.ouroboros,
    fastDegreesPerSec = 720f,
    slowDegreesPerSec = 90f,
    direction = RingDirection.CLOCKWISE,
    returnToRestOnDisconnect = true,
    modifier = modifier,
    diameter = diameter
)

@Composable
fun ChainsRing(
    phase: OuroborosPhase,
    onReturnedToRest: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 260.dp
) = RotatingRingImage(
    phase = phase,
    onReturnedToRest = onReturnedToRest,
    drawableRes = R.drawable.chains,
    fastDegreesPerSec = 1440f,   // 2x Ouroboros
    slowDegreesPerSec = 180f,
    direction = RingDirection.COUNTER_CLOCKWISE,
    returnToRestOnDisconnect = false,
    modifier = modifier,
    diameter = diameter
)

@Composable
fun SunRing(
    phase: OuroborosPhase,
    onReturnedToRest: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 260.dp
) = RotatingRingImage(
    phase = phase,
    onReturnedToRest = onReturnedToRest,
    drawableRes = R.drawable.sun,
    fastDegreesPerSec = 1080f,   // faster than Ouroboros, slower than Chains
    slowDegreesPerSec = 135f,
    direction = RingDirection.CLOCKWISE,
    returnToRestOnDisconnect = false,
    modifier = modifier,
    diameter = diameter
)

@Composable
fun DoubleSunRing(
    phase: OuroborosPhase,
    onReturnedToRest: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 260.dp
) = RotatingRingImage(
    phase = phase,
    onReturnedToRest = onReturnedToRest,
    drawableRes = R.drawable.double_sun,
    fastDegreesPerSec = 1800f,   // faster than Sun
    slowDegreesPerSec = 225f,
    direction = RingDirection.CLOCKWISE,
    returnToRestOnDisconnect = false,
    modifier = modifier,
    diameter = diameter
)
