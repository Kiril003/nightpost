package local.phantom.companion.feature.stream.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import local.phantom.companion.core.design.motion.PhantomMotion
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.component.SheetWindowBars
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace

/*
 * Один аркуш мапи на всі, що піднімаються з неї.
 *
 * До 03.10 SheetHost відкривав пʼятнадцять `ModalBottomSheet` і кожен сам
 * собі вирішував вигляд зі старої теми: тло `surfaceBase`, пелена
 * `surfaceDeep`, ручка Material у кольорі, якого на `raised` майже не видно,
 * і жоден не фарбував смуги свого вікна — тож під темним аркушем на світлому
 * телефоні стояла біла смуга навігації (A52, 02.10). Еталон — шар «Мапа»
 * (MapHomeControls.MapLayersSheet), він уже v1.2; тут той самий рецепт,
 * написаний один раз.
 */

/** Верхні кути аркуша — `PhantomRadius.sheet`, як у шарів мапи. */
internal val MapSheetShape: Shape = RoundedCornerShape(topStart = PhantomRadius.sheet, topEnd = PhantomRadius.sheet)

/**
 * Аркуш мапи у власному вікні.
 *
 * [scrim] = false — лише для досьє: за ним стоїть саме те місце, яке людина
 * щойно торкнула, і камера піднімає його над краєм (SheetCameraLift).
 *
 * `SheetWindowBars()` стоїть першим у вмісті: саме там `LocalView` належить
 * вікну аркуша, і лише звідти можна перефарбувати його смуги.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    scrim: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        // Розгорнутий аркуш доходив до самого верху вікна, і ручка лягала під
        // годинник і значки рядка стану. Аркуш зупиняється під ним.
        modifier = Modifier.statusBarsPadding(),
        shape = MapSheetShape,
        containerColor = c.raised,
        contentColor = c.text,
        tonalElevation = 0.dp,
        scrimColor = if (scrim) c.scrim else Color.Transparent,
        dragHandle = { MapSheetHandle() },
    ) {
        SheetWindowBars()
        content()
    }
}

/**
 * Аркуш місця в самій мапі, без власного вікна: вікно [MapSheet] ловило дотик
 * повз аркуш, тож повторний дотик у місце закривав його замість вибору.
 * Поза аркушем дотики йдуть мапі; сам аркуш їх не пропускає.
 */
@Composable
internal fun MapPlaceSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val instant = PhantomMotion.reducedMotion()
        val cap by animateDpAsState(
            maxHeight * if (expanded) 0.92f else 0.55f,
            animationSpec = if (instant) snap() else spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
            label = "placeSheetCap",
        )
        val enter = if (instant) {
            EnterTransition.None
        } else {
            slideInVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, IntOffset.VisibilityThreshold)) { it }
        }
        AnimatedVisibility(visibleState = shown, enter = enter) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = cap)
                    .offset { IntOffset(0, drag.coerceAtLeast(0f).roundToInt()) }
                    .clip(MapSheetShape)
                    .background(c.raised)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = rememberDraggableState { d -> drag += d },
                            orientation = Orientation.Vertical,
                            onDragStopped = {
                                val far = with(density) { PLACE_SHEET_FLING.toPx() }
                                when {
                                    drag > far -> if (expanded) expanded = false else onDismissRequest()
                                    drag < -far / 2 -> expanded = true
                                }
                                drag = 0f
                            },
                        )
                        .clickable { expanded = !expanded },
                    contentAlignment = Alignment.Center,
                ) { MapSheetHandle() }
                content()
            }
        }
    }
}

private val PLACE_SHEET_FLING = 96.dp

/**
 * Ручка аркуша — `lineStrong`. Звичайна `line` на `raised` уночі дає близько
 * 1.2:1, тобто ручки, за яку тягнуть, не видно.
 */
@Composable
internal fun MapSheetHandle() {
    val c = LocalPhantomColors.current
    Box(
        Modifier
            .padding(vertical = PhantomSpace.s3)
            .width(36.dp)
            .height(4.dp)
            .clip(CircleShape)
            .background(c.lineStrong),
    )
}

/**
 * Та сама поверхня без власного вікна: кути, тло й ручка [MapSheet].
 *
 * `ModalBottomSheet` відкриває окреме вікно, і кадр активності його не бачить;
 * кадри аркушів малюють цю поверхню, щоб знімок показував саме те, що несе
 * [MapSheet], а не схожу підробку.
 */
@Composable
internal fun MapSheetSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MapSheetShape)
            .background(c.raised),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MapSheetHandle() }
        content()
    }
}

// ── Текст аркуша: ролі v1.2 і кольори токенів ─────────────────────────────
//
// Старі ролі (PhantomKicker / PhantomCaption / PhantomDataText / PhantomBody)
// беруть колір зі старої теми й старі кеглі (підпис 11sp проти 12sp v1.2).
// Аркуші мапи говорять ролями PhantomText, як веб: заголовок title2, рядок
// callout, примітка footnote, підпис caption; числа — з `tnum`.

@Composable
internal fun SheetText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalPhantomColors.current.text,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
    tnum: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = if (tnum) style.copy(fontFeatureSettings = "tnum") else style,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

/** Назва аркуша: місце, точка, лінія. */
@Composable
internal fun SheetTitle(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) =
    SheetText(text, PhantomText.title2, modifier, LocalPhantomColors.current.text, maxLines = maxLines)

/** Рядок аркуша: підпис дії, значення поля. */
@Composable
internal fun SheetBody(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalPhantomColors.current.text,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    tnum: Boolean = false,
) = SheetText(text, PhantomText.callout, modifier, color, fontWeight, maxLines, tnum = tnum)

/** Тихий рядок під назвою або під значенням. */
@Composable
internal fun SheetNote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalPhantomColors.current.text2,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    tnum: Boolean = false,
) = SheetText(text, PhantomText.footnote, modifier, color, fontWeight, maxLines, tnum = tnum)

/** Найдрібніше: підпис поля, час біля рейсу. */
@Composable
internal fun SheetSmall(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalPhantomColors.current.text3,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    tnum: Boolean = false,
) = SheetText(text, PhantomText.caption, modifier, color, fontWeight, maxLines, tnum = tnum)

/** Назва групи всередині аркуша — слово, не капс; як `text-footnote font-semibold` на вебі. */
@Composable
internal fun SheetSection(text: String, modifier: Modifier = Modifier) =
    SheetText(
        text,
        PhantomText.footnote,
        modifier,
        LocalPhantomColors.current.text2,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )

// ── Сегменти: режими маршруту, вкладки хаба ───────────────────────────────

/**
 * Обраний сегмент — як на вебі (ListFolders): мʼяка бурштинова заливка,
 * бурштинова волосина, бурштинове слово. НЕ залитий бурштин: залита на смузі
 * рівно одна дія — старт, і друга заливка забрала б у неї ранг.
 */
@Composable
internal fun Modifier.sheetSegment(selected: Boolean, shape: Shape = CircleShape): Modifier {
    val c = LocalPhantomColors.current
    return if (selected) {
        this.clip(shape).background(c.accentSoft).border(1.dp, c.accentLine, shape)
    } else {
        this.clip(shape)
    }
}

/** Чорнило сегмента: обраний — `accentText`, решта — `text2`. */
@Composable
internal fun sheetSegmentInk(selected: Boolean): Color {
    val c = LocalPhantomColors.current
    return if (selected) c.accentText else c.text2
}

/** Доріжка сегментів: волосина `line` без заливки — однаково читається на `surface` і на `raised`. */
@Composable
internal fun Modifier.sheetSegmentTrack(shape: Shape = CircleShape): Modifier {
    val c = LocalPhantomColors.current
    return this.clip(shape).border(1.dp, c.line, shape)
}

// ── Пігулка факту і знак PHANTOM ──────────────────────────────────────────

/**
 * Пігулка факту («95», «дизель», «туалет»): тло `overlay`, слово `text2`.
 * Не PhantomChip — та ще говорить старою темою; тут тон на щабель вище за
 * аркуш, як у плиток дій.
 */
@Composable
internal fun SheetPill(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color = LocalPhantomColors.current.overlay,
    ink: Color = LocalPhantomColors.current.text2,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(fill)
            .padding(horizontal = 10.dp, vertical = PhantomSpace.s1),
        contentAlignment = Alignment.Center,
    ) {
        SheetText(text, PhantomText.footnote, color = ink, maxLines = 1)
    }
}

/**
 * Знак PHANTOM, намальований з того самого шляху, що й заставка
 * (`PhantomMark.PATH`, core-design). Значок `lc_phantom_mark` живе в `:app`,
 * і звідси його не дістати; копія шляху тут розійшлась би з оригіналом.
 */
@Composable
internal fun PhantomMarkGlyph(tint: Color, modifier: Modifier = Modifier) {
    val path = androidx.compose.runtime.remember {
        androidx.compose.ui.graphics.vector.PathParser()
            .parsePathString(local.phantom.companion.core.design.component.PhantomMark.PATH)
            .toPath()
    }
    androidx.compose.foundation.Canvas(modifier) {
        val unit = minOf(size.width, size.height) / local.phantom.companion.core.design.component.PhantomMark.VIEWPORT
        val dx = (size.width - unit * local.phantom.companion.core.design.component.PhantomMark.VIEWPORT) / 2f
        val dy = (size.height - unit * local.phantom.companion.core.design.component.PhantomMark.VIEWPORT) / 2f
        withTransform({
            translate(dx, dy)
            scale(unit, unit, pivot = Offset.Zero)
        }) {
            drawPath(path, tint)
        }
    }
}

/**
 * Ряд сегментів: доріжка-волосина, а в ній рівні сегменти [SheetSegment].
 * Той самий вибір на категорії мітки, вкладках хаба й режимах маршруту.
 */
@Composable
internal fun SheetSegments(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .sheetSegmentTrack()
            .padding(3.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Один сегмент: слово (і, може, знак) у чорнилі [sheetSegmentInk]; обраний — [sheetSegment]. */
@Composable
internal fun androidx.compose.foundation.layout.RowScope.SheetSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val ink = sheetSegmentInk(selected)
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 42.dp)
            .sheetSegment(selected)
            .clickable(role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = label
            }
            .padding(horizontal = PhantomSpace.s2),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            Box(Modifier.width(6.dp))
        }
        SheetText(
            label,
            PhantomText.subhead,
            color = ink,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * Поле вводу аркуша: тло `overlay`, кут `control`, слово `text`, курсор
 * бурштиновий. Одне поле на назву мітки, імʼя лінії й пошук хаба.
 */
@Composable
internal fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    fieldModifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.control)
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = local.phantom.companion.core.design.theme.PhantomTouchFloor)
            .clip(shape)
            .background(c.overlay)
            .padding(horizontal = PhantomSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Box(Modifier.width(PhantomSpace.s2))
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = PhantomText.callout.copy(color = c.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = fieldModifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) SheetText(placeholder, PhantomText.callout, color = c.text3, maxLines = 1)
                    inner()
                }
            },
        )
        if (trailing != null) {
            Box(Modifier.width(PhantomSpace.s2))
            trailing()
        }
    }
}
