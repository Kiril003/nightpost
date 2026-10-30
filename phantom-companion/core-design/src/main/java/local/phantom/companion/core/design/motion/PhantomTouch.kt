package local.phantom.companion.core.design.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Μ6 — the touch verb.
 *
 * A control that has taken a finger must say so in the same frame it was
 * touched, not when the route finally changes or the request finally
 * returns. On a weak phone those are hundreds of milliseconds apart, and a
 * target that stays perfectly still through them reads as broken — the
 * operator taps again, and now the app has two of everything.
 *
 * [PhantomMotion.gesture] is the only spec here on purpose: it is the
 * finger's own spring, and an `Animatable` driven by it inherits in-flight
 * velocity, so a second tap takes the animation over instead of queueing
 * behind it. Everything writes to a graphics layer and nothing else, so a
 * press can never move a neighbour or reflow a row.
 */

/** The one dominant action on a surface — the voice orb, the PTT dome. */
const val PRESS_DEPTH_PRIMARY: Float = 0.90f

/** Icons, rail slots, ordinary buttons: they can afford a visible dip. */
const val PRESS_DEPTH_CONTROL: Float = 0.94f

/** Cards, rows, whole panels — a big surface dipping 6% reads as broken. */
const val PRESS_DEPTH_SURFACE: Float = 0.985f

/** Below this a «press» stops reading as an answer and starts reading as a glitch. */
internal const val PRESS_DEPTH_FLOOR: Float = 0.80f

internal fun pressTarget(pressed: Boolean, depth: Float): Float =
    if (pressed) depth.coerceIn(PRESS_DEPTH_FLOOR, 1f) else 1f

/** For any target whose `clickable`/`combinedClickable` owns an interaction source. */
@Composable
fun Modifier.pressResponse(
    interactionSource: InteractionSource,
    depth: Float = PRESS_DEPTH_CONTROL,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    return pressResponse(pressed, depth)
}

/** For targets that run their own `detectTapGestures` and already know. */
@Composable
fun Modifier.pressResponse(
    pressed: Boolean,
    depth: Float = PRESS_DEPTH_CONTROL,
): Modifier {
    val spec = PhantomMotion.gesture
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed, depth) {
        scale.animateTo(pressTarget(pressed, depth), spec)
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
