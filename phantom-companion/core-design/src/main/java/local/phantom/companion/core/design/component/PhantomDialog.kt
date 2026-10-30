package local.phantom.companion.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace

/**
 * The dialog — v1.2's answer to the stock M3 `AlertDialog`.
 *
 * The stock one inherits the theme's colours now (see `phantomColorScheme`),
 * but not its grammar: its buttons are two `TextButton`s that both read the
 * accent as INK — amber words on a light `overlay` measure 1.5:1 by day — and
 * its title is a 24sp headline. Here the surface is `overlay` at the `sheet`
 * radius, the title is `title2`, the sentence is `body` in `text2`, the answer
 * is ONE filled button ([PrimaryButton], or [DangerButton] when [danger]) and
 * the way out is a [GhostButton] in `text2` (§7.8).
 *
 * [content] is for the rare dialog that has to hold more than a sentence — a
 * field, a short list. Copy goes in [text].
 */
@Composable
fun PhantomDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    text: String? = null,
    dismissLabel: String? = null,
    /** The confirm cannot be taken back (delete, erase, leave) — `danger` fill. */
    danger: Boolean = false,
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        PhantomDialogPane(
            title = title,
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            text = text,
            dismissLabel = dismissLabel,
            danger = danger,
            confirmEnabled = confirmEnabled,
            content = content,
        )
    }
}

/**
 * The pane alone, without the window — what [PhantomDialog] puts in its
 * `Dialog`. Public so a screen that already owns a window (a sheet, a
 * full-screen confirm) can draw the same pane inline.
 */
@Composable
fun PhantomDialogPane(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    dismissLabel: String? = null,
    danger: Boolean = false,
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = modifier
            .widthIn(max = DIALOG_MAX)
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.sheet))
            .background(c.overlay)
            .semantics { paneTitle = title }
            .padding(start = PhantomSpace.s6, end = PhantomSpace.s6, top = PhantomSpace.s6, bottom = PhantomSpace.s4),
    ) {
        Text(text = title, style = PhantomText.title2, color = c.text)
        if (text != null) {
            Spacer(Modifier.height(PhantomSpace.s2))
            Text(text = text, style = PhantomText.body, color = c.text2)
        }
        if (content != null) {
            Spacer(Modifier.height(PhantomSpace.s3))
            content()
        }
        Spacer(Modifier.height(PhantomSpace.s5))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2, androidx.compose.ui.Alignment.End),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            if (dismissLabel != null) GhostButton(label = dismissLabel, onClick = onDismiss)
            if (danger) {
                DangerButton(label = confirmLabel, onClick = onConfirm, enabled = confirmEnabled)
            } else {
                PrimaryButton(label = confirmLabel, onClick = onConfirm, enabled = confirmEnabled)
            }
        }
    }
}

/** §7.7: on a wide screen a sheet «becomes a centred dialog (max 480)». */
private val DIALOG_MAX = 480.dp
