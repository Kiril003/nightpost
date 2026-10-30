package local.phantom.companion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace

/**
 * Те, що лишилось від фурнітури старого екрана налаштувань.
 *
 * Сам екран (SettingsRealm) розпущено в розділи Профілю, і з ним пішли пошук,
 * розкривні групи, «ціна важеля» й шапка. Тут лишились лише шматки, які ще
 * кличуть дочірні екрани інших пакетів — ліцензія, джерела даних, термін
 * зберігання, моделі, діагностика, — і всі вони тепер пласкі, на токенах v1.2
 * (`LocalPhantomColors`), без скла й без літер Material: той самий рядок, та
 * сама поверхня, що в Профілі, — щоб дочірній екран не виглядав гостем зі
 * старого застосунку, поки його не перебудує власник.
 */

/**
 * A fact PHANTOM cannot observe — an OS grant, a pairing file, a system
 * toggle — sampled now and re-sampled every time the app returns to the
 * foreground. Granting happens on a system screen, so a value read once on
 * entry is stale exactly when the operator comes back to look at it.
 */
@Composable
internal fun <T> rememberOnResume(key: Any?, read: () -> T): T {
    var value by remember(key) { mutableStateOf(read()) }
    val latestRead by rememberUpdatedState(read)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, key) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) value = latestRead()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return value
}

/**
 * Підпис групи. Колись — моноширинні капітелі з розрядкою; тепер той самий
 * тихий caption, що стоїть над групами Профілю.
 */
@Composable
fun SettingsKicker(label: String, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    Text(text = label, style = PhantomText.caption, color = c.text3, modifier = modifier)
}

/** Заголовок секції дочірнього екрана — той самий підпис групи. */
@Composable
internal fun SectionHeader(label: String) {
    SettingsKicker(label = label)
}

/**
 * Один рядок усередині картки. [value] стоїть праворуч третім рівнем тексту;
 * [valueTone] — коли сама відповідь є попередженням.
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueTone: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    val spoken = listOfNotNull(title, value).joinToString(". ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(role = Role.Button, onClick = onClick)
                        .semantics { contentDescription = spoken }
                } else {
                    Modifier
                },
            )
            .padding(vertical = PhantomSpace.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = PhantomSpace.s3)) {
            Text(text = title, style = PhantomText.body, color = c.text)
        }
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = PhantomText.callout,
                color = valueTone ?: c.text3,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) {
            Spacer(modifier = Modifier.width(PhantomSpace.s2))
            Icon(
                painter = painterResource(DesignR.drawable.lc_chevron_right),
                contentDescription = null,
                tint = c.text3,
                modifier = Modifier.size(PhantomSize.iconS),
            )
        }
    }
}

/**
 * Дія у два кроки, яка лишається на своєму рядку. Перший дотик міняє напис на
 * [confirmLabel]. Використовується там, де модалка була б важча за наслідок.
 */
@Composable
internal fun SettingsConfirmAction(
    label: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    var armed by remember { mutableStateOf(false) }
    val armedState = stringResource(R.string.settings_a11y_armed)
    val restState = stringResource(R.string.settings_a11y_two_step)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = PhantomTouchFloor)
            .clip(CircleShape)
            .border(1.dp, if (armed) c.danger else c.lineStrong, CircleShape)
            .clickable(role = Role.Button) {
                if (armed) {
                    onConfirm()
                    armed = false
                } else {
                    armed = true
                }
            }
            .semantics { stateDescription = if (armed) armedState else restState }
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (armed) confirmLabel else label,
            style = PhantomText.subhead,
            fontWeight = FontWeight.SemiBold,
            color = c.danger,
        )
    }
}

/** Картка дочірнього екрана: піднята поверхня, як група рядків у Профілі. */
@Composable
internal fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.sheet))
            .background(c.raised)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
        content = content,
    )
}

@Composable
internal fun <T> SettingsSegmented(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.control)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .border(1.dp, c.lineStrong, shape)
            .selectableGroup()
            .testTag("segmented"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            val on = item == selected
            if (i > 0) {
                val seam = !on && items[i - 1] != selected
                Box(Modifier.width(1.dp).fillMaxHeight().background(if (seam) c.line else c.lineStrong))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = PhantomTouchFloor)
                    .background(if (on) c.overlay else c.surface)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(item) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(item),
                    style = PhantomText.subhead,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) c.text else c.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = PhantomSpace.s1),
                )
            }
        }
    }
}
