package local.phantom.companion.core.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.haptics.PhantomHaptics
import local.phantom.companion.core.design.layout.HideBottomChrome
import local.phantom.companion.core.design.motion.MotionTokens
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace

/**
 * The subsystem surface — a fluid bottom sheet for interactions too rich
 * for a dropdown and too transient for a chamber (attachment sources,
 * quick pickers, half-screen tools).
 *
 * Doctrine:
 *  - It rises on the canonical snap spring (180 / 0.75) and can be thrown
 *    back down with the finger — the drag IS the dismiss affordance, the
 *    handle merely advertises it.
 *  - v1.2 sheet (PHANTOM_DESIGN_SYSTEM.md §7.7): an opaque `raised` surface
 *    with a 20dp top radius, a 36×4 `lineStrong` grabber 8dp from the top,
 *    and `scrim` behind. No lit-from-above highlight — v1.2 has no gradients.
 *  - The world behind it dims under a scrim (no live blur — a RenderEffect
 *    per frame is the exact battery/frame cost the Continuum's depth optics
 *    already refused; opacity does the receding for free).
 *  - Edge-to-edge: the sheet's glass runs under the gesture bar to the
 *    physical bottom of the display; only the CONTENT is inset above the
 *    navigation bar. There is no frame for it to stop at anymore.
 *
 * Back-press handling stays with the caller (core-design carries no
 * androidx.activity dependency): wrap `open` in a BackHandler at the
 * call site.
 */
@Composable
fun PhantomSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 0 = fully hidden, 1 = at rest. Kept composed while settling out so
    // the exit is a flight, not a removal.
    val progress = remember { Animatable(0f) }
    val view = LocalView.current
    // Μ5 — the finger's own spring for the settle-home, and no flight at all
    // when the operator has asked the phone to stop moving.
    val settleSpec = PhantomMotion.gesture
    val instant = PhantomMotion.reducedMotion()
    LaunchedEffect(open, instant) {
        val target = if (open) 1f else 0f
        if (open) PhantomHaptics.tick(view)
        if (instant) progress.snapTo(target) else progress.animateTo(target, MotionTokens.snapSpringFloat)
    }
    // Reading `progress.value` straight in the body recomposed this whole
    // surface on every animation frame; the flip is the only thing that matters.
    val settled by remember { derivedStateOf { progress.value <= 0.001f } }
    if (!open && settled) return

    // Острівець — це навігація, а аркуш уже забрав екран собі: у нього є свій
    // вихід (тяга вниз, тап по скриму, назад). Рейка поверх аркуша лише
    // зʼїдала його останній рядок — кнопку «закрити». Резерв при цьому НЕ
    // згортається: світ за скримом лишається стояти там, де стояв.
    HideBottomChrome()

    val c = LocalPhantomColors.current
    val scope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismiss)
    var sheetHeightPx by remember { mutableIntStateOf(0) }

    Box(modifier = modifier.fillMaxSize()) {
        // The dim — the conversation recedes behind its own darkness. Tap
        // anywhere on it to let the subsystem go.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .background(c.scrim)
                .pointerInput(Unit) { detectTapGestures { latestDismiss() } },
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { sheetHeightPx = it.height }
                .graphicsLayer {
                    // Spring overshoot past 1 lifts the sheet a breath above
                    // its resting line and lets it settle — organic, honest.
                    translationY = (1f - progress.value) * sheetHeightPx
                }
                .clip(SheetShape)
                .background(c.raised)
                .pointerInput(Unit) {
                    // Drag-to-dismiss. Downward drag maps 1:1 onto the
                    // sheet's own height; release below half lets it go,
                    // otherwise it springs home under the finger's spring.
                    var dragPx = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dragPx = (1f - progress.value) * sheetHeightPx },
                        onDragEnd = {
                            if (progress.value < 0.5f) latestDismiss()
                            else scope.launch { progress.animateTo(1f, settleSpec) }
                        },
                        onDragCancel = {
                            scope.launch { progress.animateTo(1f, settleSpec) }
                        },
                    ) { change, dy ->
                        change.consume()
                        val h = sheetHeightPx.toFloat().coerceAtLeast(1f)
                        dragPx = (dragPx + dy).coerceAtLeast(0f)
                        scope.launch { progress.snapTo((1f - dragPx / h).coerceIn(0f, 1f)) }
                    }
                }
                // The glass bleeds under the gesture bar; the words don't.
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding(),
        ) {
            // The handle — a quiet capsule that says «this surface moves».
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = PhantomSpace.s2, bottom = PhantomSpace.s1)
                    .width(GRABBER_W)
                    .height(GRABBER_H)
                    .clip(PhantomRadii.Pill)
                    .background(c.lineStrong),
            )
            content()
        }
    }
}

private val SheetShape = androidx.compose.foundation.shape.RoundedCornerShape(
    topStart = PhantomRadius.sheet,
    topEnd = PhantomRadius.sheet,
)
private val GRABBER_W = 36.dp
private val GRABBER_H = 4.dp
