package local.phantom.companion.core.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import local.phantom.companion.core.design.motion.MotionTokens
import local.phantom.companion.core.design.tokens.LocalPhantomColors

/**
 * Знак Nightpost — «Естафета»: одне світло, розрізане навпіл (щілина 2, крок 4
 * на сітці 24). Джерело — docs/brand/assets/symbol/nightpost-symbol-24.svg;
 * копії в res/ звіряє LaunchMarkTest. Імʼя обʼєкта лишилось старим — його
 * читає код, а не людина.
 */
object PhantomMark {
    const val PATH = "M11 2A8 8 0 0 0 11 18ZM13 6A8 8 0 0 1 13 22Z"
    const val VIEWPORT = 24f

    /** Одиниця знака на заставці: ic_splash_mark.xml ставить його в масштабі 4 на полотні 288dp (80 dp заввишки). */
    val SplashUnit: Dp = 4.dp
}

/** Полотно зі знаком по центру вікна — кадр, на якому системна заставка віддає застосунок. */
@Composable
fun PhantomMarkFrame(canvas: Color, ink: Color, modifier: Modifier = Modifier) {
    val path = remember { PathParser().parsePathString(PhantomMark.PATH).toPath() }
    Canvas(modifier.fillMaxSize()) {
        drawRect(canvas)
        val unit = PhantomMark.SplashUnit.toPx()
        val half = PhantomMark.VIEWPORT / 2f * unit
        withTransform({
            translate(size.width / 2f - half, size.height / 2f - half)
            scale(unit, unit, pivot = Offset.Zero)
        }) {
            drawPath(path, ink)
        }
    }
}

/**
 * Тримає над [content] останній кадр системної заставки і один раз його згасає.
 *
 * Кадр — у розвʼязаній палітрі застосунку (полотно й лінія бурштину з
 * [LocalPhantomColors]), а не в темі телефона: під завісою вже лежить ескіз
 * домівки в тій самій палітрі, і згасання знімає лише знак, не перефарбовує
 * екран. Системна заставка перед нею — з values/values-night за нічним режимом
 * застосунку, який SplashNight тримає в тій самій фазі (збірка 16). До того
 * завіса йшла за `isSystemInDarkTheme`, і світлий телефон увечері бачив
 * кремовий кадр, що згасав у чорний застосунок.
 */
@Composable
fun PhantomSplashHost(content: @Composable () -> Unit) {
    val colors = LocalPhantomColors.current
    val veil = remember { Animatable(1f) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Холодний старт морить годинник кадрів; без межі завіса лишалась висіти.
        kotlinx.coroutines.withTimeoutOrNull(SPLASH_DEADLINE_MS) {
            kotlinx.coroutines.delay(SPLASH_HOLD_MS.toLong())
            veil.animateTo(targetValue = 0f, animationSpec = MotionTokens.dismissQuart)
        }
        done = true
    }

    Box(Modifier.fillMaxSize()) {
        content()
        if (!done) {
            PhantomMarkFrame(
                canvas = colors.canvas,
                ink = colors.accentLine,
                modifier = Modifier
                    .zIndex(10f)
                    .graphicsLayer { alpha = veil.value },
            )
        }
    }
}

internal const val SPLASH_HOLD_MS = 150
internal const val SPLASH_FADE_MS = MotionTokens.DISMISS_DURATION_MS
internal const val SPLASH_DEADLINE_MS = (SPLASH_HOLD_MS + SPLASH_FADE_MS).toLong() + 1_250L
