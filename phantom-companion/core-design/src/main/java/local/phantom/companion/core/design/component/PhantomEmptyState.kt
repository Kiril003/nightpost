package local.phantom.companion.core.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.R
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace

/**
 * The four faces of "there is nothing to show", and the one rule that picks
 * between them.
 *
 * Seven hand-rolled empty states were counted on 2026-08-15 and every one of
 * them could say only ONE thing: «порожньо». A screen that has not finished
 * reading, a screen whose read failed, and a screen that genuinely holds
 * nothing are three different facts about the world, and collapsing them
 * tells the operator he owns no saved places when in truth the query threw.
 *
 * [Mismatch] is the fourth, and it is here because this product shipped it:
 * a card printed a correct count above a row that drew nothing. Neither
 * "empty" nor "failed" is true there — the app contradicted ITSELF, and the
 * only honest thing to show is that it did. [voidFace] is what makes that
 * reachable: a screen that passes its header count in cannot render the
 * contradiction silently.
 *
 * The shape of this generalises `feature-stream`'s `CardFace`/`cardFace`,
 * which solved the same problem for widget cards and could not be reused
 * because it is `internal` to that module.
 */
enum class PhantomVoid { Loading, Empty, Failed, Mismatch }

/**
 * `null` means "there is content — draw it". Order is the whole argument:
 * rows win over everything (they are real data, whatever else is in flight);
 * a read still running outranks a failed one; a failed read explains its own
 * emptiness, so it speaks before the count is doubted. Only a read believed
 * to have SUCCEEDED, holding nothing, under a header promising [expected]
 * items, is a contradiction.
 */
fun voidFace(
    rows: Int,
    loading: Boolean = false,
    readable: Boolean = true,
    expected: Int? = null,
): PhantomVoid? = when {
    rows > 0 -> null
    loading -> PhantomVoid.Loading
    !readable -> PhantomVoid.Failed
    expected != null && expected > 0 -> PhantomVoid.Mismatch
    else -> PhantomVoid.Empty
}

/**
 * [title] overrides the face's own copy for screens that can say something
 * more specific than «порожньо» — the default is never blank, so a screen
 * cannot ship a wordless empty state by forgetting a string.
 *
 * [expected] is only read by [PhantomVoid.Mismatch], where it is the number
 * the operator can see on screen contradicting the rows he cannot.
 */
@Composable
fun PhantomEmptyState(
    face: PhantomVoid,
    modifier: Modifier = Modifier,
    title: String? = null,
    detail: String? = null,
    expected: Int? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    val headline = title ?: defaultTitle(face, expected)
    val body = detail ?: defaultDetail(face)

    // §7.11: a Lucide glyph in `text-3`, a `title-2` line, one `callout`
    // sentence in `text-2`, then ONE primary button. Max width 320.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s6)
            // Announced, not just drawn: the operator who cannot see the tray
            // glyph still has to learn that the read failed. Merged so the
            // headline and the detail arrive as one utterance, not two.
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        Icon(
            painter = painterResource(glyph(face)),
            contentDescription = null,
            tint = when (face) {
                PhantomVoid.Failed, PhantomVoid.Mismatch -> c.danger
                else -> c.text3
            },
            modifier = Modifier.size(GLYPH),
        )
        Text(
            text = headline,
            style = PhantomText.title2,
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = TEXT_MAX),
        )
        if (body != null) {
            Text(
                text = body,
                style = PhantomText.callout,
                color = c.text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = TEXT_MAX),
            )
        }
        if (actionLabel != null && onAction != null) {
            PrimaryButton(
                label = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = PhantomSpace.s2),
            )
        }
    }
}

/** Shape, not hue: these four must be told apart in greyscale. */
@DrawableRes
internal fun glyph(face: PhantomVoid): Int = when (face) {
    PhantomVoid.Loading -> R.drawable.lc_hourglass
    PhantomVoid.Empty -> R.drawable.lc_inbox
    PhantomVoid.Failed -> R.drawable.lc_alert_circle
    PhantomVoid.Mismatch -> R.drawable.lc_alert_triangle
}

private val GLYPH = 48.dp
private val TEXT_MAX = 320.dp

@Composable
private fun defaultTitle(face: PhantomVoid, expected: Int?): String = when (face) {
    PhantomVoid.Loading -> stringResource(R.string.void_loading_title)
    PhantomVoid.Empty -> stringResource(R.string.void_empty_title)
    PhantomVoid.Failed -> stringResource(R.string.void_failed_title)
    PhantomVoid.Mismatch ->
        stringResource(R.string.void_mismatch_title, expected ?: 0)
}

@Composable
private fun defaultDetail(face: PhantomVoid): String? = when (face) {
    PhantomVoid.Loading -> null
    PhantomVoid.Empty -> null
    PhantomVoid.Failed -> stringResource(R.string.void_failed_detail)
    PhantomVoid.Mismatch -> stringResource(R.string.void_mismatch_detail)
}
