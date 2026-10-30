package local.phantom.companion.feature.stream.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.haptics.PhantomHaptics
import local.phantom.companion.core.design.motion.MotionTokens
import local.phantom.companion.core.design.motion.PhantomMotion
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.feature.stream.R

/** До середини — і до верху. Третього положення немає: воно б лише плуталось. */
internal enum class SearchDetent { HALF, FULL }

/** Половина лишає мапу над шторкою живою — заради цього все й затівалось. */
private const val HALF_FRACTION = 0.55f

/** Не 1f: смужка землі вгорі каже, що мапа не зникла, і приймає тап «закрити». */
private const val FULL_FRACTION = 0.94f

/** Частка ВИДИМОЇ висоти, яку треба протягнути вниз, щоб зійти на щабель. */
private const val STEP_DOWN = 0.28f

/** Догори — коротший поріг: підняти шторку дешевше, ніж її загубити. */
private const val STEP_UP_DP = 48f

@Stable
internal class SearchSheetState(initial: SearchDetent = SearchDetent.HALF) {
    var detent by mutableStateOf(initial)

    /**
     * 0 — шторки не видно, 1 — стоїть на щаблі. Частка, а не пікселі:
     * висоту в пікселях знає лише перший вимір, а `Animatable`
     * створюється до нього — з пікселів довелось би починати з нуля,
     * тобто з одного кадру повністю піднятої шторки.
     */
    val progress = Animatable(0f)
}

@Composable
internal fun rememberSearchSheetState(): SearchSheetState = remember { SearchSheetState() }

/**
 * Шторка пошуку: аркуш знизу, мапа зверху — жива, з камерою і тапами.
 *
 * Вигляд — той самий, що в аркуша «Шари» ([MapLayersSheet]): піднята
 * поверхня `raised`, кути `PhantomRadius.sheet`, ручка `lineStrong`. Сам
 * `ModalBottomSheet` сюди не взято свідомо: він кладе пелену на всю мапу й
 * забирає в неї дотики, а ця шторка існує саме для того, щоб мапа над нею
 * лишалась живою (щабель «половина»).
 *
 * Тяга живе на шапці (держак + поле), а не на всій шторці: список має власну
 * прокрутку, і якби шторка ловила вертикаль скрізь, кожен свайп по рядках
 * ставав би торгом «хто перший». Шапка ж не прокручується ніколи.
 *
 * Висота — це РОЗКЛАДКА щабля, а тяга — `graphicsLayer`. Тому перерахунок
 * списку стається двічі за жест (на старті й на осіданні), а не в кожному
 * кадрі під пальцем.
 */
@Composable
internal fun SearchSheetFrame(
    state: SearchSheetState,
    onDismiss: () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismiss)
    val instant = PhantomMotion.reducedMotion()
    // Пружок пальця читається ТУТ: у `PhantomMotion.gesture` @Composable-геттер,
    // а колбеки тяги — вже не композиція.
    val settleSpec = PhantomMotion.gesture

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val screenPx = with(density) { maxHeight.toPx() }
        val stepUpPx = with(density) { STEP_UP_DP.dp.toPx() }
        val fraction = if (state.detent == SearchDetent.FULL) FULL_FRACTION else HALF_FRACTION
        val visiblePx = screenPx * fraction
        // Жест читає ЖИВЕ число крізь це вікно, а не через ключ `pointerInput`:
        // щабель перемикається посеред тяги, і ключ, що змінився, зніс би
        // детектор разом із пальцем, який ще на склі.
        val liveVisiblePx by rememberUpdatedState(visiblePx)
        val liveScreenPx by rememberUpdatedState(screenPx)

        // Приліт — з-під нижнього краю на той самий канонічний пружок.
        LaunchedEffect(Unit) {
            PhantomHaptics.tick(view)
            if (instant) state.progress.snapTo(1f)
            else state.progress.animateTo(1f, MotionTokens.snapSpringFloat)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(fraction)
                .graphicsLayer { translationY = (1f - state.progress.value) * visiblePx }
                .clip(SheetTop)
                .background(c.raised)
                .border(1.dp, c.line, SheetTop),
            // IME-резерв живе НЕ тут: вміст бере bottomChromeInsets() (per-axis
            // MAX ime/рейки) один раз — imePadding зверху давав би СУМУ і мертву
            // смугу висотою рейки над клавіатурою.
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var raw = 0f
                        detectVerticalDragGestures(
                            onDragStart = { raw = 0f },
                            onDragEnd = {
                                val settled = state.progress.value
                                when {
                                    settled > 1f - STEP_DOWN -> scope.launch {
                                        state.progress.animateTo(1f, settleSpec)
                                    }
                                    // З повної — на половину, а не одразу геть:
                                    // щабель між ними для того й існує.
                                    state.detent == SearchDetent.FULL -> {
                                        state.detent = SearchDetent.HALF
                                        scope.launch { state.progress.snapTo(1f) }
                                        PhantomHaptics.tick(view)
                                    }
                                    else -> latestDismiss()
                                }
                            },
                            onDragCancel = {
                                scope.launch { state.progress.animateTo(1f, settleSpec) }
                            },
                        ) { change, dy ->
                            change.consume()
                            raw += dy
                            // Вгору з половини — щабель перемикається ПОСЕРЕД жесту.
                            // Частку перебазовуємо на нову висоту, інакше верхній
                            // край стрибнув би з-під пальця на приріст 39% екрана.
                            if (raw < -stepUpPx && state.detent == SearchDetent.HALF) {
                                val half = liveScreenPx * HALF_FRACTION
                                val full = liveScreenPx * FULL_FRACTION
                                val rebased = (state.progress.value * half / full).coerceIn(0f, 1f)
                                state.detent = SearchDetent.FULL
                                raw = 0f
                                scope.launch { state.progress.snapTo(rebased) }
                                PhantomHaptics.tick(view)
                            } else {
                                val next = (state.progress.value - dy / liveVisiblePx).coerceIn(0f, 1f)
                                scope.launch { state.progress.snapTo(next) }
                            }
                        }
                    },
            ) {
                Grabber()
                header()
            }
            content()
        }
    }
}

/** Верхні кути аркуша — ті самі, що в «Шарів». */
private val SheetTop = RoundedCornerShape(topStart = PhantomRadius.sheet, topEnd = PhantomRadius.sheet)

/** Ручка 36×4, `lineStrong`. */
@Composable
private fun Grabber() {
    val c = LocalPhantomColors.current
    val desc = stringResource(R.string.map_search_grabber)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(c.lineStrong),
        )
    }
}
