package local.phantom.companion.core.design.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.R
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace

/**
 * Wave 1 §5 Executor C — was `SaveConfirmationStrip` in `app/.../ui/settings/`,
 * one hardcoded «✓ збережено» pill. Moved here and generalized the moment a
 * second, unrelated screen (map POI save/delete/rename) needed the same
 * affordance instead of a system Toast: reusing the settings-private,
 * one-message version verbatim would have forced every caller's copy through
 * "✓ збережено" (dishonest on a failure path) or invented a second, parallel
 * component — exactly the "second word for the same idea" this app's design
 * rules exist to outlaw.
 *
 * [ConfirmKind.Refusal] is plain ink on a plain raised capsule, **never** `danger` —
 * that hue is reserved for Level-1 seizure / key-mismatch, and a save that
 * merely failed is not that.
 */
enum class ConfirmKind { Success, Refusal }

@Composable
fun PhantomInlineConfirm(
    message: String?,
    kind: ConfirmKind = ConfirmKind.Success,
    visibleAt: Long?,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(visibleAt) {
        if (visibleAt != null) {
            visible = true
            delay(VISIBLE_MS)
            visible = false
        }
    }

    Box(modifier = modifier.wrapContentSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = PhantomMotion.enter,
            exit = PhantomMotion.exit,
        ) {
            // v1.2: a flat capsule, no edge. Success is the accent's own soft
            // wash with the accent at ink weight; a refusal is a plain raised
            // surface in plain ink. 12sp `caption` — 12 is the floor (§3.2).
            val ink = if (kind == ConfirmKind.Refusal) c.text else c.accentText
            val fill = if (kind == ConfirmKind.Refusal) c.raised else c.accentSoft
            Row(
                modifier = Modifier
                    .clip(PhantomRadii.Pill)
                    .background(fill)
                    .padding(horizontal = PhantomSpace.s3, vertical = PhantomSpace.s1 + PhantomSpace.s0_5),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (kind == ConfirmKind.Success) {
                    Icon(
                        painter = painterResource(R.drawable.lc_check),
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(PhantomSize.iconS),
                    )
                    Spacer(modifier = Modifier.width(PhantomSpace.s1 + PhantomSpace.s0_5))
                }
                Text(
                    text = message.orEmpty(),
                    style = PhantomText.caption,
                    color = ink,
                )
            }
        }
    }
}

internal const val VISIBLE_MS: Long = 1_500L
