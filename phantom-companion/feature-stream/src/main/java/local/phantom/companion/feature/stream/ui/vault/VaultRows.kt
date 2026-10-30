package local.phantom.companion.feature.stream.ui.vault

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.data.vault.VaultEntity
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.vault.VaultGroup
import local.phantom.companion.feature.stream.vault.VaultKind
import local.phantom.companion.feature.stream.vault.previewOf

internal const val REVEAL_SECONDS = 6

/** Everything a row can do, so the row composables stay layout-only. */
internal data class VaultRowActions(
    val armed: Boolean,
    val revealSeconds: Int?,
    val onReveal: () -> Unit,
    val onHide: () -> Unit,
    val onArmErase: () -> Unit,
    val onDisarmErase: () -> Unit,
    val onConfirmErase: () -> Unit,
)

/** Рівні цифри для лічильників і секунд: рядок не «стрибає», коли число міняється. */
private val Tabular = PhantomText.footnote.copy(fontFeatureSettings = "tnum")

@Composable
internal fun VaultGroupHeader(group: VaultGroup, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = PhantomSpace.s4, end = PhantomSpace.s4, top = PhantomSpace.s5, bottom = PhantomSpace.s2),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(group.kind.title).sentence(),
                style = PhantomText.headline,
                color = if (group.kind == VaultKind.Unopenable) c.danger else c.text,
                modifier = Modifier.weight(1f),
            )
            Text(text = group.entries.size.toString(), style = Tabular, color = c.text3)
        }
        Spacer(modifier = Modifier.height(PhantomSpace.s0_5))
        Text(
            text = stringResource(group.kind.blurb).sentence(),
            style = PhantomText.footnote,
            color = c.text3,
        )
    }
}

@Composable
internal fun VaultRow(
    entity: VaultEntity,
    kind: VaultKind,
    actions: VaultRowActions,
    modifier: Modifier = Modifier,
) {
    when (kind) {
        VaultKind.Unopenable -> BrokenRow(entity, actions, modifier)
        VaultKind.Secret -> SecretRow(entity, actions, modifier)
        VaultKind.Note -> NoteRow(entity, actions, modifier)
        VaultKind.Trace -> TraceRow(entity, actions, modifier)
    }
}

/**
 * Картка запису: піднята поверхня з радіусом картки. Рамка лише в зламаного —
 * кольору небезпеки, бо саме він вимагає рішення.
 */
@Composable
private fun RowShell(
    broken: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.raised)
            .then(if (broken) Modifier.border(1.dp, c.danger, shape) else Modifier)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
        content = content,
    )
}

@Composable
private fun SecretRow(entity: VaultEntity, actions: VaultRowActions, modifier: Modifier) {
    val c = LocalPhantomColors.current
    val revealed = actions.revealSeconds != null
    val mask = previewOf(entity) ?: stringResource(R.string.vault_masked)

    RowShell(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entity.key,
                style = PhantomText.headline,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions.revealSeconds?.let { left ->
                Spacer(modifier = Modifier.width(PhantomSpace.s2))
                Text(text = stringResource(R.string.vault_copied, left).sentence(), style = Tabular, color = c.accentText)
            }
        }
        Spacer(modifier = Modifier.height(PhantomSpace.s1))
        Text(
            text = if (revealed) entity.value else mask,
            style = PhantomText.body,
            color = if (revealed) c.text else c.text3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        actions.revealSeconds?.let { RevealMeter(it) }
        Spacer(modifier = Modifier.height(PhantomSpace.s3))
        VaultActionBar(actions = actions) {
            VaultPill(
                label = stringResource(
                    if (revealed) R.string.vault_hide else R.string.vault_reveal,
                ),
                ink = c.accentText,
                fill = c.accentSoft,
                onClick = if (revealed) actions.onHide else actions.onReveal,
            )
        }
    }
}

@Composable
private fun NoteRow(entity: VaultEntity, actions: VaultRowActions, modifier: Modifier) {
    val c = LocalPhantomColors.current
    val label = labelOf(entity)

    RowShell(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = PhantomText.footnote,
                color = c.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(PhantomSpace.s1))
        }
        Text(
            text = entity.value,
            style = PhantomText.callout,
            color = c.text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s3))
        VaultActionBar(actions = actions, trailing = { RowStamp(entity.updatedAt) }) {
            CopyPill(actions)
        }
    }
}

@Composable
private fun TraceRow(entity: VaultEntity, actions: VaultRowActions, modifier: Modifier) {
    val c = LocalPhantomColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(start = PhantomSpace.s4, top = PhantomSpace.s0_5, bottom = PhantomSpace.s0_5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entity.value,
            style = PhantomText.callout,
            color = c.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(PhantomSpace.s3))
        VaultActionBar(actions = actions, compact = true)
    }
}

@Composable
private fun BrokenRow(entity: VaultEntity, actions: VaultRowActions, modifier: Modifier) {
    val c = LocalPhantomColors.current
    RowShell(broken = true, modifier = modifier) {
        Text(
            text = entity.key,
            style = PhantomText.headline,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s1))
        Text(
            text = stringResource(R.string.vault_sealed_shut).sentence(),
            style = PhantomText.subhead,
            color = c.danger,
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s0_5))
        Text(
            text = stringResource(R.string.vault_sealed_shut_why).sentence(),
            style = PhantomText.footnote,
            color = c.text2,
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s3))
        VaultActionBar(actions = actions)
    }
}

@Composable
private fun CopyPill(actions: VaultRowActions) {
    val c = LocalPhantomColors.current
    val left = actions.revealSeconds
    VaultPill(
        label = if (left != null) {
            stringResource(R.string.vault_copied, left)
        } else {
            stringResource(R.string.vault_copy)
        },
        ink = c.accentText,
        fill = c.accentSoft,
        onClick = if (left != null) actions.onHide else actions.onReveal,
    )
}

/**
 * The erase two-step. Arming replaces the whole action row rather than raising
 * a dialog, so the decision stays attached to the row it will destroy.
 */
@Composable
private fun VaultActionBar(
    actions: VaultRowActions,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    primary: (@Composable () -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    val width = if (compact) Modifier else Modifier.fillMaxWidth()
    // transitionSpec is not a @Composable scope, so the verb is read out here.
    val swap = PhantomMotion.swap
    AnimatedContent(
        targetState = actions.armed,
        transitionSpec = { swap },
        label = "vault-erase-step",
        modifier = modifier.then(width),
    ) { armed ->
        if (armed) {
            Row(
                modifier = width,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (!compact) {
                    Text(
                        text = stringResource(R.string.vault_erase_ask).sentence(),
                        style = PhantomText.footnote,
                        color = c.danger,
                        modifier = Modifier.weight(1f),
                    )
                }
                VaultPill(
                    label = stringResource(R.string.vault_erase_yes),
                    ink = c.danger,
                    onClick = actions.onConfirmErase,
                )
                Spacer(modifier = Modifier.width(PhantomSpace.s2))
                VaultPill(
                    label = stringResource(R.string.vault_erase_no),
                    ink = c.text,
                    onClick = actions.onDisarmErase,
                )
            }
        } else {
            Row(
                modifier = width,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (!compact) {
                    Box(modifier = Modifier.weight(1f)) { trailing?.invoke() }
                }
                if (primary != null) {
                    primary()
                    Spacer(modifier = Modifier.width(PhantomSpace.s2))
                }
                VaultPill(
                    label = stringResource(R.string.vault_delete),
                    ink = c.text2,
                    onClick = actions.onArmErase,
                )
            }
        }
    }
}

@Composable
private fun RowStamp(updatedAt: Long) {
    val c = LocalPhantomColors.current
    val stamp = remember(updatedAt) {
        DateUtils.getRelativeTimeSpanString(
            updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }
    Text(text = stamp, style = Tabular, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun RevealMeter(secondsLeft: Int) {
    val c = LocalPhantomColors.current
    val fraction by animateFloatAsState(
        targetValue = secondsLeft.toFloat() / REVEAL_SECONDS,
        animationSpec = PhantomMotion.meter,
        label = "vault-reveal-meter",
    )
    Spacer(modifier = Modifier.height(PhantomSpace.s2))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(CircleShape)
            .background(c.line),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(2.dp)
                .clip(CircleShape)
                .background(c.accentLine),
        )
    }
}

// Internal ids are not names: «stream:9f2c-…» told the operator nothing the
// note's own first line does not.
private fun labelOf(entity: VaultEntity): String? = entity.key
    .takeUnless { it.startsWith("stream:") || it == "map.recent" }
    ?.takeIf { it.isNotBlank() }
