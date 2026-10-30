package local.phantom.companion.feature.stream.ui.map

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.component.PhantomMark
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace

// Одна мова для всього, що лежить на мапі або в її аркушах (v1.2).
//
// До цього файла поверхня мапи говорила трьома: нові контроли (поле пошуку,
// чипи дій, аркуш «Шари») стояли на токенах, а пігулки, платівка, картка
// машини, пошук і офлайн-панель — на старій «склянці» з бурштином стану і
// моноширинними кікерами. Власник це й побачив: «контраст між старим і
// новим». Тут лише токени: raised/overlay/line, текстові ролі Manrope,
// значки lc_*, ціль дотику 48dp.

/** Ціль пальця — закон дотику, а не форми; намальоване буває меншим. */
internal val MapTouch: Dp = PhantomSpace.touchMinAndroid

/** Намальована висота пігулки: на крок нижче за чип дії, щоб слово не важило як кнопка. */
internal val MapPillHeight: Dp = PhantomSize.buttonM

/** Хто говорить пігулкою: просто слово, небезпека чи застереження. */
internal enum class MapTone { Neutral, Danger, Warning }

internal fun MapTone.ink(c: PhantomColors): Color = when (this) {
    MapTone.Neutral -> c.text
    MapTone.Danger -> c.danger
    MapTone.Warning -> c.warning
}

internal fun MapTone.edge(c: PhantomColors): Color = when (this) {
    MapTone.Neutral -> c.line
    MapTone.Danger -> c.danger
    MapTone.Warning -> c.warning
}

/**
 * Пігулка мапи — речення про стан, що лежить на землі.
 *
 * Піднята поверхня, волосина [MapTone.edge]. Дотик (якщо є) росте до
 * 48dp зовнішньою коробкою, а скло лишається [MapPillHeight].
 */
@Composable
internal fun MapPill(
    modifier: Modifier = Modifier,
    tone: MapTone = MapTone.Neutral,
    onClick: (() -> Unit)? = null,
    spoken: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    val glass: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .heightIn(min = MapPillHeight)
                .clip(CircleShape)
                .background(c.raised)
                .border(1.dp, tone.edge(c), CircleShape)
                .padding(horizontal = PhantomSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            content = content,
        )
    }
    if (onClick == null) {
        Box(modifier) { glass() }
    } else {
        Box(
            modifier = modifier
                .heightIn(min = MapTouch)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .then(if (spoken != null) Modifier.semantics(mergeDescendants = true) { contentDescription = spoken } else Modifier),
            contentAlignment = Alignment.Center,
        ) { glass() }
    }
}

/** Слово пігулки: footnote, один рядок, обрізається трьома крапками, а не посеред літери. */
@Composable
internal fun MapPillText(
    text: String,
    color: Color = LocalPhantomColors.current.text,
    weight: FontWeight? = null,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = PhantomText.footnote,
        color = color,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Крапка стану — колір каже раніше за слово, тож слово поруч обовʼязкове. */
@Composable
internal fun MapDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(PhantomSpace.s2).clip(CircleShape).background(color))
}

/** Значок lc_* у розмірі рядка. */
@Composable
internal fun MapIcon(
    @DrawableRes icon: Int,
    tint: Color = LocalPhantomColors.current.text2,
    size: Dp = PhantomSize.iconM,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(size),
    )
}

/**
 * Кругла дія на мапі: піднятий диск із волосиною, як [MapActionChip], тільки без
 * слова — для того, що вже назване поруч (✓ і × у пігулці малювання, компас).
 * [accent] — головна дія місця: бурштин і чорнило на ньому.
 */
@Composable
internal fun MapRoundAction(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    drawn: Dp = MapPillHeight,
) {
    val c = LocalPhantomColors.current
    Box(
        modifier = modifier
            .size(MapTouch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(drawn)
                .clip(CircleShape)
                .background(if (accent) c.accent else c.raised)
                .border(1.dp, if (accent) c.accent else c.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MapIcon(icon, tint = if (accent) c.onAccent else c.text)
        }
    }
}

/** Дія в кінці рядка: значок малий, ціль — ні. */
@Composable
internal fun MapRowAction(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = LocalPhantomColors.current.text2,
) {
    Box(
        modifier = Modifier
            .size(MapTouch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        MapIcon(icon, tint = tint)
    }
}

/** Плаваюча картка над мапою: піднята, з волосиною. */
@Composable
internal fun MapCard(
    modifier: Modifier = Modifier,
    edge: Color = LocalPhantomColors.current.line,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.card)
    Column(
        modifier = modifier
            .clip(shape)
            .background(c.raised)
            .border(1.dp, edge, shape),
        content = content,
    )
}

/**
 * Група рядків усередині аркуша мапи: на піднятому аркуші — [PhantomColors.overlay],
 * межі між рядками — волосина [PhantomColors.line], як у групах Профілю.
 */
@Composable
internal fun MapRowGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.card))
            .background(c.overlay),
        content = content,
    )
}

/**
 * Волосина між рядками групи — від тексту, не від краю: значок лишається колонкою.
 * `lineStrong`, а не `line`: група стоїть на `overlay`, і `line` на ньому
 * зливається (уночі 0x232831 на 0x242934, удень — той самий 0xE6DED3).
 */
@Composable
internal fun MapRowDivider(inset: Dp = MapRowTextInset) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(LocalPhantomColors.current.lineStrong),
    )
}

/** Де починається текст рядка зі значком: поле 16 + значок 20 + зазор 16. */
internal val MapRowTextInset: Dp = PhantomSpace.s4 + PhantomSize.iconM + PhantomSpace.s4

/** Підпис секції аркуша: caption text3, не моноширинний кікер. */
@Composable
internal fun MapSectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = PhantomText.caption,
        color = LocalPhantomColors.current.text3,
        modifier = modifier,
    )
}

/**
 * Рядок аркуша: значок text2, назва body, підпис footnote text3, хвіст.
 * Мінімум 56dp, як рядок Профілю.
 */
@Composable
internal fun MapListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = LocalPhantomColors.current.text3,
    titleColor: Color = LocalPhantomColors.current.text,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    spoken: String? = null,
    titleMaxLines: Int = 1,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MapRowHeight)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(if (spoken != null) Modifier.semantics(mergeDescendants = true) { contentDescription = spoken } else Modifier)
            .padding(start = PhantomSpace.s4, end = if (trailing != null) PhantomSpace.s1 else PhantomSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(PhantomSize.iconM), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(PhantomSpace.s4))
        }
        Column(
            modifier = Modifier.weight(1f).padding(vertical = PhantomSpace.rowV),
        ) {
            Text(
                text = title,
                style = PhantomText.body,
                color = titleColor,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = PhantomText.footnote,
                    color = subtitleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

internal val MapRowHeight: Dp = 56.dp

/**
 * Дія словом у кінці рядка або під карткою: 48dp, без заливки.
 * [tone] Danger — для другого дотику руйнівної дії.
 */
@Composable
internal fun MapTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: MapTone? = null,
    enabled: Boolean = true,
) {
    val c = LocalPhantomColors.current
    Box(
        modifier = modifier
            .heightIn(min = MapTouch)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = PhantomSpace.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // Дія — слово з великої: рядки ресурсів пишуться малими (вони ж
            // стоять і посеред речень), а кнопка починає речення сама.
            text = label.replaceFirstChar { it.titlecase() },
            style = PhantomText.subhead,
            color = when (tone) {
                MapTone.Danger -> c.danger
                MapTone.Warning -> c.warning
                MapTone.Neutral -> c.text2
                null -> c.accentText
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Головна дія місця: бурштинова заливка, чорнило [PhantomColors.onAccent], 48dp. */
@Composable
internal fun MapAccentButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    val c = LocalPhantomColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PhantomSize.buttonL)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .background(c.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = PhantomSpace.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            MapIcon(icon, tint = c.onAccent)
            Spacer(Modifier.width(PhantomSpace.s2))
        }
        Text(label, style = PhantomText.headline, color = c.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Чип аркуша: overlay-пігулка зі словом; [selected] — бурштин дії. 48dp. */
@Composable
internal fun MapChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    @DrawableRes icon: Int? = null,
) {
    val c = LocalPhantomColors.current
    Box(
        modifier = modifier
            .heightIn(min = MapTouch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = MapPillHeight)
                .clip(CircleShape)
                .background(if (selected) c.accentSoft else c.overlay)
                .border(1.dp, if (selected) c.accentLine else c.line, CircleShape)
                .padding(horizontal = PhantomSpace.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                MapIcon(icon, tint = if (selected) c.accentText else c.text2, size = PhantomSize.iconS)
                Spacer(Modifier.width(PhantomSpace.s2))
            }
            Text(
                text = label,
                style = PhantomText.subhead,
                color = if (selected) c.accentText else c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Цифри приладу: Manrope з табличними цифрами, щоб «100 м» не тремтіло на зумі. */
internal val MapFigureStyle: TextStyle
    get() = PhantomText.caption.copy(fontFeatureSettings = "tnum")

/**
 * Знак PHANTOM — там, де говорить PHANTOM (рядок «Запитати PHANTOM»).
 * Контур з [PhantomMark.PATH]; та сама «P», що в нижній смузі й на заставці.
 */
@Composable
internal fun PhantomMarkGlyph(
    tint: Color = LocalPhantomColors.current.accentText,
    size: Dp = PhantomSize.iconM,
) {
    val path = remember { PathParser().parsePathString(PhantomMark.PATH).toPath() }
    Canvas(Modifier.size(size)) {
        val unit = this.size.minDimension / PhantomMark.VIEWPORT
        scale(unit, unit, pivot = Offset.Zero) { drawPath(path, tint) }
    }
}
