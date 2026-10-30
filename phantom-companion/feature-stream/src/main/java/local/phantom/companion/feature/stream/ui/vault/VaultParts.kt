package local.phantom.companion.feature.stream.ui.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.LocalOnline
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.vault.UnlockAttempt
import local.phantom.companion.core.design.R as DesignR

/**
 * Замок, навколо якого зібрана вся поверхня: закритий і нерухомий, поки
 * замкнено; дужка піднята, коли відчинено.
 *
 * Розпізнавання в польоті колись «дихало» фіолетовою загравою на власному
 * нескінченному годиннику. Сигнал лишився — зник лише рух: поки чекаємо на
 * палець, обідок кола стає бурштиновим і товщим. Стан сказаний кольором і
 * словом на кнопці («чекаю · палець на сенсорі»), а не пульсом.
 */
@Composable
internal fun VaultMark(
    open: Boolean,
    busy: Boolean,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val lift by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = PhantomMotion.stateChange,
        label = "vault-mark-lift",
    )
    val disc = c.raised
    val rim = if (busy) c.accentLine else c.lineStrong

    Canvas(modifier) {
        val unit = minOf(size.width, size.height)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val ring = unit * 0.46f
        val stroke = unit * 0.035f

        drawCircle(color = disc, radius = ring, center = Offset(cx, cy))
        drawCircle(
            color = rim,
            radius = ring,
            center = Offset(cx, cy),
            style = Stroke(width = if (busy) unit * 0.03f else unit * 0.012f),
        )

        val bodyW = unit * 0.36f
        val bodyH = unit * 0.28f
        val bodyTop = cy + unit * 0.015f
        drawRoundRect(
            color = tone,
            topLeft = Offset(cx - bodyW / 2f, bodyTop),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(unit * 0.05f),
        )

        val shackleR = bodyW * 0.30f
        val legLen = unit * 0.06f
        val shackleCy = bodyTop - legLen - unit * 0.05f * lift
        val shackleCx = cx + shackleR * 0.55f * lift
        drawArc(
            color = tone,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(shackleCx - shackleR, shackleCy - shackleR),
            size = Size(shackleR * 2f, shackleR * 2f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = tone,
            start = Offset(shackleCx - shackleR, shackleCy),
            end = Offset(shackleCx - shackleR, bodyTop),
            strokeWidth = stroke,
        )
        drawLine(
            color = tone,
            start = Offset(shackleCx + shackleR, shackleCy),
            end = Offset(shackleCx + shackleR, shackleCy + legLen * (1f - lift)),
            strokeWidth = stroke,
        )

        // Шпарина — виріз кольору диска, а не окрема фарба.
        drawCircle(
            color = disc,
            radius = unit * 0.022f,
            center = Offset(cx, bodyTop + bodyH * 0.42f),
        )
    }
}

/** Підпис групи — той самий рецепт, що RowGroup «Профілю»: caption, text3. */
@Composable
internal fun VaultCaption(text: String, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    Text(
        text = text.sentence(),
        style = PhantomText.caption,
        color = c.text3,
        modifier = modifier.padding(start = PhantomSpace.s4, bottom = PhantomSpace.s2, top = PhantomSpace.s2),
    )
}

/** Піднята група рядків: raised, радіус аркуша. Одна картка на групу, без рамки й скла. */
@Composable
internal fun VaultCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalPhantomColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.sheet))
            .background(c.raised),
        content = content,
    )
}

/**
 * What is sealed, what is only covered by the database key, what never leaves.
 * Every line here is a claim about the code in `core-data/vault`; change one
 * only together with the other.
 */
@Composable
internal fun VaultShieldLedger(modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    val online = LocalOnline.current

    Column(modifier = modifier.fillMaxWidth()) {
        VaultCaption(text = stringResource(R.string.vault_shield_title))
        VaultCard {
            ShieldRow(
                head = R.string.vault_shield_values_head,
                level = R.string.vault_shield_level_sealed,
                levelInk = c.success,
                body = R.string.vault_shield_values,
            )
            ShieldRule()
            ShieldRow(
                head = R.string.vault_shield_names_head,
                level = R.string.vault_shield_level_partial,
                levelInk = c.warning,
                body = R.string.vault_shield_names,
            )
            ShieldRule()
            ShieldRow(
                head = R.string.vault_shield_network_head,
                level = R.string.vault_shield_level_offline,
                levelInk = c.success,
                body = if (online) {
                    R.string.vault_shield_network
                } else {
                    R.string.vault_shield_network_offline
                },
            )
            ShieldRule()
            ShieldRow(
                head = R.string.vault_shield_clipboard_head,
                level = R.string.vault_shield_level_temporary,
                levelInk = c.text3,
                body = R.string.vault_shield_clipboard,
            )
        }
    }
}

@Composable
private fun ShieldRow(
    @StringRes head: Int,
    @StringRes level: Int,
    levelInk: Color,
    @StringRes body: Int,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(head).sentence(),
                style = PhantomText.body,
                color = c.text,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(PhantomSpace.s2))
            Text(
                text = stringResource(level).sentence(),
                style = PhantomText.footnote,
                fontWeight = FontWeight.SemiBold,
                color = levelInk,
            )
        }
        Spacer(modifier = Modifier.height(PhantomSpace.s0_5))
        Text(
            text = stringResource(body).sentence(),
            style = PhantomText.footnote,
            color = c.text2,
        )
    }
}

@Composable
private fun ShieldRule() {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PhantomSpace.s4)
            .height(1.dp)
            .background(c.line),
    )
}

/**
 * Мала дія рядка: капсула на поверхні overlay (або на власній заливці), напис
 * роллю subhead. Без рамки й моно-капсу — так само, як решта кнопок застосунку.
 */
@Composable
internal fun VaultPill(
    label: String,
    ink: Color,
    modifier: Modifier = Modifier,
    fill: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    Box(
        modifier = modifier
            .heightIn(min = PhantomSize.buttonM)
            .clip(CircleShape)
            .background(if (fill == Color.Unspecified) c.overlay else fill)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = PhantomSpace.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.sentence(),
            style = PhantomText.subhead,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Головна дія замкненої поверхні — єдина бурштинова заливка на екрані. Поки
 * чекаємо на палець, кнопка відступає в нейтральну поверхню й каже, чого чекає.
 */
@Composable
internal fun VaultUnlockButton(waiting: Boolean, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomSize.buttonL)
            .clip(CircleShape)
            .background(if (waiting) c.overlay else c.accent)
            .clickable(enabled = !waiting, role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s6, vertical = PhantomSpace.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(
                if (waiting) R.string.vault_unlock_waiting else R.string.vault_recognise,
            ).sentence(),
            style = PhantomText.headline,
            color = if (waiting) c.text2 else c.onAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun VaultRefusal(attempt: UnlockAttempt, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    val message = refusalMessage(attempt) ?: return
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(message).sentence(),
            style = PhantomText.callout,
            color = c.danger,
            textAlign = TextAlign.Center,
        )
        if (attempt == UnlockAttempt.NotRecognised) {
            Spacer(modifier = Modifier.height(PhantomSpace.s1))
            Text(
                text = stringResource(R.string.vault_auth_failed_hint).sentence(),
                style = PhantomText.footnote,
                color = c.text2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@StringRes
internal fun refusalMessage(attempt: UnlockAttempt): Int? = when (attempt) {
    UnlockAttempt.NotRecognised -> R.string.vault_auth_failed
    UnlockAttempt.Cancelled -> R.string.vault_auth_cancelled
    UnlockAttempt.NoDeviceLock -> R.string.vault_no_device_lock
    UnlockAttempt.Idle, UnlockAttempt.Waiting -> null
}

@Composable
internal fun rememberBiometricLaunch(
    onRequested: () -> Unit,
    onSuccess: () -> Unit,
    onRefused: (UnlockAttempt) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val title = stringResource(R.string.vault_recognise)
    val subtitle = stringResource(R.string.vault_confirm_you)
    val cancel = stringResource(R.string.vault_cancel)
    val info = remember(title, subtitle, cancel) {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(cancel)
            .build()
    }
    return remember(activity, executor, info, onRequested, onSuccess, onRefused) {
        {
            val canAuth = BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            // A device with no screen lock at all lands here, and on such a
            // device there is nobody to authenticate. Opening anyway — which
            // this branch used to do — hands the vault to whoever holds it.
            if (activity == null || canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                onRefused(UnlockAttempt.NoDeviceLock)
                return@remember
            }
            onRequested()
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    onRefused(UnlockAttempt.NotRecognised)
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    onRefused(
                        if (code == BiometricPrompt.ERROR_USER_CANCELED ||
                            code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            UnlockAttempt.Cancelled
                        } else {
                            UnlockAttempt.NotRecognised
                        },
                    )
                }
            }
            BiometricPrompt(activity, executor, callback).authenticate(info)
        }
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Flagged sensitive so Android 13+ leaves the value out of the paste toast. */
internal fun copyAsSecret(context: Context, value: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("", value).apply {
        description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    runCatching { manager.setPrimaryClip(clip) }
}

/** Takes back what this screen put there, and only that. */
internal fun revokeClipboard(context: Context, expected: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val current = runCatching {
        manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }.getOrNull()
    if (current != expected) return
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}

/** Стан сховища у верхній смузі: точка й слово, без моно-капсу й рамки. */
@Composable
internal fun VaultStatusChip(locked: Boolean) {
    val c = LocalPhantomColors.current
    val dot by animateColorAsState(
        targetValue = if (locked) c.text3 else c.accent,
        animationSpec = PhantomMotion.stateChangeColor,
        label = "vault-status-dot",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Замкнено — замок; відчинено — бурштинова точка «тут зараз відкрито».
        if (locked) {
            Icon(
                painter = painterResource(DesignR.drawable.lc_lock),
                contentDescription = null,
                tint = c.text2,
                modifier = Modifier.size(PhantomSize.iconS),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(PhantomSpace.s2)
                    .clip(CircleShape)
                    .background(dot),
            )
        }
        Spacer(modifier = Modifier.width(PhantomSpace.s2))
        Text(
            // Ресурс написаний капсом («ЗАМКНЕНО»): мова форми — слово з великої.
            text = stringResource(
                if (locked) R.string.vault_status_locked else R.string.vault_status_open,
            ).lowercase().sentence(),
            style = PhantomText.footnote,
            fontWeight = FontWeight.SemiBold,
            color = if (locked) c.text2 else c.accentText,
        )
    }
}

/** Рядки писались під старий капс малими; мова форми — речення з великої. */
internal fun String.sentence(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
