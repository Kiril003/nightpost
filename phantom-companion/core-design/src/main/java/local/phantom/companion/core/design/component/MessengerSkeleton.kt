package local.phantom.companion.core.design.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.tokens.LocalPhantomColors

/**
 * Ескіз першого екрана месенджера — домівки Nightpost (`PhantomHome.tsx`) —
 * на час, поки веб ще не змалював себе.
 *
 * До збірки 16 між заставкою й месенджером людина бачила знак із підписом
 * «Відкриваю месенджер…», а під прозорим WebView — кремове полотно Compose:
 * екран-очікування, що ще й блимав білим. Тепер з першого кадру стоїть
 * спокійний ескіз того, що зараз зʼявиться: шапка з диском знака, капсула
 * питання з мікрофоном, рядок розмови й дві картки.
 *
 * Той самий ескіз малює заставка вебу (`index.html`, `#boot .sk`) і запасний
 * кадр React (`BootFrame` в App.tsx) — тими самими числами: CSS px у WebView —
 * це dp. Тож ланцюжок «рідний ескіз → #boot → React» не стрибає: змінюється
 * лише власник розмітки. Числа живуть у [MessengerSkeletonSpec], і
 * `MessengerSkeletonParityTest` звіряє їх із CSS заставки.
 *
 * Ні тексту, ні руху. Текст — бо шрифт вебу на першому кадрі ще не приїхав,
 * і гліфи стрибнули б на передачі. Рух — бо три власники ескізу стартують у
 * різні миті, і пульс у різних фазах блимав би на кожній передачі. Знак у
 * диску — справжній (це імʼя застосунку), решта — лише форми: жодних
 * вигаданих рядків, імен чи чисел.
 */
@Composable
fun MessengerSkeleton(modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    val s = MessengerSkeletonSpec
    Column(
        modifier
            .fillMaxSize()
            .background(c.canvas)
            .clearAndSetSemantics { }
            .padding(top = s.topPad),
    ) {
        Row(
            Modifier.fillMaxWidth().height(s.headHeight).padding(start = s.side, end = s.headEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(s.headGap),
        ) {
            Box(Modifier.size(s.disc).clip(PhantomRadii.Pill).background(c.text), contentAlignment = Alignment.Center) {
                MarkGlyph(color = c.canvas, size = s.disc * s.discGlyphShare)
            }
            Block(c.raised, Modifier.size(s.titleWidth, s.titleHeight))
            Spacer(Modifier.weight(1f))
            Block(c.raised, Modifier.size(s.pillWidth, s.pillHeight))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = s.side, end = s.side, top = s.askTop),
            horizontalArrangement = Arrangement.spacedBy(s.askGap),
        ) {
            Block(c.raised, Modifier.weight(1f).height(s.askHeight))
            Block(c.raised, Modifier.size(s.askHeight))
        }
        Column(
            Modifier.fillMaxWidth().padding(top = s.rowTop).height(s.rowHeight).padding(horizontal = s.side),
            verticalArrangement = Arrangement.spacedBy(s.rowGap, Alignment.CenterVertically),
        ) {
            Block(c.raised, Modifier.fillMaxWidth(s.lineShare).height(s.lineHeight))
            Block(c.raised, Modifier.fillMaxWidth(s.thinShare).height(s.thinHeight))
        }
        Block(c.raised, Modifier.padding(start = s.side, end = s.side, top = s.firstCardTop).fillMaxWidth().height(s.cardHeight), PhantomRadii.LargeShape)
        Block(c.raised, Modifier.padding(start = s.side, end = s.side, top = s.cardGap).fillMaxWidth().height(s.cardHeight), PhantomRadii.LargeShape)
    }
}

/** Брусок ескізу. Без форми — капсула (радіус = половина висоти, як `border-radius` у #boot). */
@Composable
private fun Block(color: Color, modifier: Modifier, shape: Shape = PhantomRadii.Pill) {
    Box(modifier.clip(shape).background(color))
}

@Composable
private fun MarkGlyph(color: Color, size: Dp) {
    val path = remember { PathParser().parsePathString(PhantomMark.PATH).toPath() }
    Canvas(Modifier.width(size).height(size)) {
        val unit = this.size.minDimension / PhantomMark.VIEWPORT
        scale(unit, unit, pivot = androidx.compose.ui.geometry.Offset.Zero) { drawPath(path, color) }
    }
}

/**
 * Числа ескізу — ті самі, що в `#boot .sk` (phantom-os/src/frontend/index.html)
 * і в розмітці `PhantomHome.tsx`, з якої обидва зняті: `pt-2` шапки, рядок
 * `h-[52px] pl-4 pr-2 gap-3`, диск `w-9`, кнопка «Розмови» `h-11`, форма
 * `mx-4 mt-3 gap-2` з капсулою й мікрофоном `h-12`, список `pt-4`.
 */
object MessengerSkeletonSpec {
    val topPad = 8.dp
    val headHeight = 52.dp
    val side = 16.dp
    val headEnd = 8.dp
    val headGap = 12.dp
    val disc = 36.dp
    const val discGlyphShare = 0.48f
    val titleWidth = 92.dp
    val titleHeight = 12.dp
    val pillWidth = 108.dp
    val pillHeight = 44.dp
    val askTop = 12.dp
    val askGap = 8.dp
    val askHeight = 48.dp
    val rowTop = 16.dp
    val rowHeight = 48.dp
    val rowGap = 6.dp
    const val lineShare = 0.42f
    val lineHeight = 12.dp
    const val thinShare = 0.64f
    val thinHeight = 10.dp
    val firstCardTop = 8.dp
    val cardGap = 12.dp
    val cardHeight = 88.dp
    /** = [PhantomRadii.Large]; число тут — для звірки з CSS #boot. */
    val cardRadius = 16.dp
}
