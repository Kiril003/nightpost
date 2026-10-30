package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.dossier.WalkWords
import local.phantom.companion.core.design.R as DesignR

/*
 * Три ваги дії на аркушах мапи, щоб ранг читався формою, а не порядком рядків:
 * одна залита кнопка, ряд рівних піднятих плиток, тихі рядки під «Ще».
 *
 * 03.10 — шкіра v1.2. Залита — `accent` з чорнилом `onAccent`; піднята —
 * на тон вище за свою землю; плаваюча над мапою — `surface` з волосиною
 * `line`. Ці примітиви ділять peers/… і nav/GuidanceChrome, тож сигнатури
 * не змінено: перефарбувалась уся сімʼя разом.
 */

private val PrimaryHeight = PhantomSize.buttonL
private val TileHeight = 64.dp
private val TileGlyph = 22.dp
private val ActionShape = RoundedCornerShape(PhantomRadius.card)

@Composable
internal fun MapPrimaryAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PrimaryHeight)
            .clip(ActionShape)
            .background(c.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(PhantomSize.iconM))
        Box(Modifier.width(PhantomSpace.s2))
        SheetText(label, PhantomText.callout, color = c.onAccent, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Друга вага: та сама висота, що й головна, але піднята тоном, не залита. */
@Composable
internal fun MapSecondaryAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val c = LocalPhantomColors.current
    val ink = if (danger) c.danger else c.text
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PrimaryHeight)
            .mapRaised(ActionShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(PhantomSize.iconM))
        Box(Modifier.width(PhantomSpace.s2))
        SheetText(label, PhantomText.callout, color = ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Ряд рівних плиток: однакова ширина — одна сітка, хоч скільки їх. */
@Composable
internal fun MapTileRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        content = content,
    )
}

@Composable
internal fun RowScope.MapActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.Unspecified,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier
            .weight(1f)
            .height(TileHeight)
            .clip(ActionShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = PhantomSpace.s1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (tint == Color.Unspecified) c.text2 else tint,
            modifier = Modifier.size(TileGlyph),
        )
        Box(Modifier.height(6.dp))
        FitLabel(label, c.text)
    }
}

/**
 * Підпис плитки, що завжди влазить: з системним шрифтом власника «Поділитись»
 * обрізалось до «Поділити…». Кегль сходить щаблями до [LABEL_MIN] і зупиняється.
 */
@Composable
internal fun FitLabel(label: String, color: Color, weight: FontWeight = FontWeight.Medium) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val width = constraints.maxWidth
        val style = PhantomText.footnote.copy(fontWeight = weight, textAlign = TextAlign.Center)
        val size = androidx.compose.runtime.remember(label, width, weight) {
            var s = LABEL_MAX.value
            while (s > LABEL_MIN.value &&
                measurer.measure(label, style.copy(fontSize = s.sp), maxLines = 1, softWrap = false).size.width > width
            ) s -= 0.5f
            s.sp
        }
        androidx.compose.material3.Text(
            text = label,
            color = color,
            style = style.copy(fontSize = size, lineHeight = size * 1.3f),
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val LABEL_MAX = 13.sp
private val LABEL_MIN = 10.sp

/**
 * Піднята поверхня аркуша: на тон вище за свою землю, без обвідки й без тіні.
 *
 * Аркуш мапи сам стоїть на `raised` ([MapSheet]), тож плитка на ньому —
 * `overlay`: `raised` на `raised` зливався б у нуль, як ручка на тій самій
 * землі. Так само зроблено в еталоні — рядки шару «Мапа» лежать на `overlay`.
 */
@Composable
internal fun Modifier.mapRaised(shape: Shape = ActionShape): Modifier {
    val c = LocalPhantomColors.current
    return this
        .clip(shape)
        .background(c.overlay)
}

/** Плаваюча поверхня над мапою (смуга маршруту, плашка навігації): `surface`, волосина `line`, тінь. */
@Composable
internal fun MapFloatingSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.sheet)
    Column(
        modifier = modifier
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape),
        content = content,
    )
}

@Composable
internal fun MapRaisedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .mapRaised()
            .padding(PhantomSpace.s4),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
        content = content,
    )
}

/** Заголовок групи всередині аркуша — слово, не капс. */
@Composable
internal fun MapGroupTitle(text: String, modifier: Modifier = Modifier) {
    SheetSection(text, modifier)
}

/** Шеврон «розгорнути»: Lucide chevron-right, повернутий униз або вгору. */
@Composable
internal fun SheetChevron(expanded: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Icon(
        painterResource(DesignR.drawable.lc_chevron_right),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(PhantomSize.iconS).rotate(if (expanded) -90f else 90f),
    )
}

/** «Ще» — рідкісне глибше; згорнуте за замовчуванням. */
@Composable
internal fun MapMoreToggle(expanded: Boolean, onToggle: () -> Unit) {
    val c = LocalPhantomColors.current
    val label = androidx.compose.ui.res.stringResource(
        if (expanded) R.string.map_sheet_less else R.string.map_sheet_more,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SheetBody(label, color = c.text2, fontWeight = FontWeight.SemiBold)
        Box(Modifier.width(PhantomSpace.s1))
        SheetChevron(expanded, c.text2)
    }
}

/** Тихий рядок «Ще»: слово на всю ширину, значок лише коли він пришвидшує. */
@Composable
internal fun MapQuietRow(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    tint: Color = Color.Unspecified,
) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (tint == Color.Unspecified) c.text2 else tint,
                modifier = Modifier.size(PhantomSize.iconM),
            )
            Box(Modifier.width(PhantomSpace.s4))
        }
        SheetBody(label)
    }
}

@Composable
internal fun walkWords(): WalkWords {
    val context = LocalContext.current
    return WalkWords(
        minutes = { context.getString(R.string.dossier_walk_minutes, it) },
        hours = { context.getString(R.string.dossier_walk_hours, it) },
        hoursMinutes = { h, m -> context.getString(R.string.dossier_walk_hours_minutes, h, m) },
    )
}

/**
 * Залита пігулка — та сама головна дія, що й [MapPrimaryAction], лише в
 * рядок: `accent` і чорнило `onAccent`. Смуга маршруту має рівно одну таку —
 * «старт». Мішень — 48dp заввишки, намальована пігулка — 40dp усередині.
 */
@Composable
internal fun MapPillPrimary(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    Box(
        modifier = modifier
            .heightIn(min = PhantomTouchFloor)
            .clip(RoundedCornerShape(PhantomRadius.composer))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(PhantomSize.buttonM)
                .clip(RoundedCornerShape(PhantomRadius.composer))
                .background(c.accent)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            SheetText(label, PhantomText.subhead, color = c.onAccent, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Тиха пігулка: волосина `lineStrong`, слово `text`. Для «прибути до», «зняти» й подібного. */
@Composable
internal fun MapPillQuiet(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    Box(
        modifier = modifier
            .heightIn(min = PhantomTouchFloor)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(PhantomSize.buttonS)
                .clip(RoundedCornerShape(PhantomRadius.composer))
                .border(1.dp, c.lineStrong, RoundedCornerShape(PhantomRadius.composer))
                .padding(horizontal = PhantomSpace.s3),
            contentAlignment = Alignment.Center,
        ) {
            SheetText(label, PhantomText.footnote, color = c.text, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
