package local.phantom.companion.feature.stream.ui.map.mount

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.motion.MotionTokens
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomDataText
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.feature.stream.R

/** Оптичний розмір рішучого органу керування (ux-map-design.md §4.1), не крок сітки. */
private val EXIT_CONTROL_SIZE = 56.dp

/** Товщина кільця відліку — вона ж і його читаність на витягнутій руці. */
private val EXIT_RING_STROKE = 3.dp

/** Хрестик усередині кільця: більший за звичайний гліф мапи, бо це виїзд. */
private val EXIT_GLYPH = 22.dp

internal const val EXIT_HINT_VISIBLE_MS = 2000L
private const val EXIT_HINT_THRESHOLD = 0.02f

/**
 * ux-map-design.md §4.1's decision-gating tier (56dp, specced there but never
 * built) meets §4.3's long-press-to-leave grammar (specced for the blackout
 * gesture). A stray tap here at windscreen distance is worse than one on
 * EtaStrip's plain stop circle — the whole map, chrome and alert pill come
 * back at once — so exit needs strictly more friction than that precedent,
 * on a control visible enough for a passenger to use without a manual.
 */
@Composable
internal fun MountExitControl(
    onExit: () -> Unit,
    /** Підказка живе вище: її просить не лише короткий тап, а й системне
     *  «назад», яке цей екран перехоплює. */
    hintVisible: Boolean,
    onShowHint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPhantomTheme.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val exitName = stringResource(R.string.mount_exit_action)

    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        AnimatedVisibility(
            visible = hintVisible,
            enter = PhantomMotion.enter,
            exit = PhantomMotion.exit,
        ) {
            PhantomDataText(
                text = stringResource(R.string.mount_exit_hint),
                color = theme.inkSecondary,
                maxLines = Int.MAX_VALUE,
                modifier = Modifier.padding(bottom = PhantomSpacings.Small),
            )
        }
        Box(
            modifier = Modifier
                .size(EXIT_CONTROL_SIZE)
                .clip(PhantomRadii.Pill)
                .background(theme.glassPanel.copy(alpha = PhantomAlpha.Near))
                // Утримання — жест, а жест не має семантики: без цієї дії
                // читач екрана бачив підписану іконку і не мав чим її
                // натиснути. Виходимо одразу — доступна дія не мусить
                // повторювати фрикцію, яка існує проти випадкового пальця.
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = exitName
                    onClick(label = exitName) { onExit(); true }
                }
                .pointerInput(onExit) {
                    detectTapGestures(
                        onPress = {
                            val holdJob = scope.launch {
                                progress.snapTo(0f)
                                progress.animateTo(1f, MotionTokens.holdConfirmLinear)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onExit()
                            }
                            tryAwaitRelease()
                            if (progress.value < 1f) {
                                holdJob.cancel()
                                if (progress.value > EXIT_HINT_THRESHOLD) onShowHint()
                                scope.launch { progress.animateTo(0f, MotionTokens.holdReleaseTween) }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(EXIT_CONTROL_SIZE)) {
                val stroke = EXIT_RING_STROKE.toPx()
                val inset = stroke / 2f
                drawArc(
                    color = theme.glassBorder,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                // The only animation this control owns is literally the
                // elapsed hold — a sweep angle driven by real progress, not
                // a decorative pulse.
                if (progress.value > 0f) {
                    drawArc(
                        color = theme.inkStrong,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.value,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
                tint = theme.inkStrong,
                modifier = Modifier.size(EXIT_GLYPH),
            )
        }
    }
}
