package local.phantom.companion.core.design.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors

/**
 * The title block EVERY chamber opens with — that is the whole value.
 *
 * The subtitle used to be 10sp JetBrains Mono at up to 4sp of tracking, which
 * on the vault read «с л і д і в   н е   з а л и ш а ю» — the letter-spaced
 * mono label v1.2 retired (§3.2: «No ALL CAPS, no letter-spaced labels»). It is
 * the `caption` role in `text2` now, one voice with the rest of the screen.
 */
@Composable
fun ChamberHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    /** Anything that belongs beside the name — a state chip, a count. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = c.text,
                fontSize = titleSize(title.length, trailing != null),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = PhantomText.caption,
                color = c.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

/** A title that wraps stops being a title. A trailing chip costs one step. */
internal fun titleSize(length: Int, hasTrailing: Boolean) = when {
    length <= (if (hasTrailing) 10 else 15) -> 32.sp
    length <= (if (hasTrailing) 15 else 20) -> 26.sp
    else -> 22.sp
}
