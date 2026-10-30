package local.phantom.companion.ui.home

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import local.phantom.companion.core.design.motion.MotionTokens

internal object FadeThrough {
    const val OUT_MS = MotionTokens.FADE_THROUGH_OUT_MS
    const val IN_MS = MotionTokens.FADE_THROUGH_IN_MS
    const val IN_DELAY_MS = MotionTokens.FADE_THROUGH_IN_DELAY_MS
    const val SCALE_FROM = 0.92f

    fun enter(reduced: Boolean): EnterTransition =
        if (reduced) {
            EnterTransition.None
        } else {
            val spec = MotionTokens.fadeThroughIn
            fadeIn(animationSpec = spec) + scaleIn(initialScale = SCALE_FROM, animationSpec = spec)
        }

    fun exit(reduced: Boolean): ExitTransition =
        if (reduced) {
            ExitTransition.None
        } else {
            fadeOut(animationSpec = MotionTokens.fadeThroughOut)
        }

    fun spec(reduced: Boolean): ContentTransform =
        ContentTransform(targetContentEnter = enter(reduced), initialContentExit = exit(reduced), sizeTransform = null)
}
