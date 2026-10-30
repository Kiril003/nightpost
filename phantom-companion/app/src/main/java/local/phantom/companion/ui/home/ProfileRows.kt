package local.phantom.companion.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.design.R as DesignR

/*
 * Рядки Профілю, яких бракувало в ProfileKit: дія на місці, стан, вибір і
 * факт. Ті самі відступи, шрифти й поверхня, що в NavRow і SwitchRow, — щоб
 * колишні панелі старого екрана налаштувань стали рядками тієї самої групи, а
 * не вклейкою зі скла посеред нового Профілю.
 *
 * Бурштин — лише на дії, яку рядок зробить від дотику. Стан («надано») —
 * зеленим, незворотне — червоним, просте значення — третім рівнем тексту.
 */

/** Яким кольором говорить праве слово рядка. */
internal enum class RowTone { Action, Done, Danger, Quiet }

@Composable
private fun RowTone.color(): Color {
    val c = LocalPhantomColors.current
    return when (this) {
        RowTone.Action -> c.accentText
        RowTone.Done -> c.success
        RowTone.Danger -> c.danger
        RowTone.Quiet -> c.text3
    }
}

/** Мінімальна висота рядка групи — та сама, що в NavRow і SwitchRow. */
private val RowMin = PhantomSpace.s12 + PhantomSpace.s2

/** Ширина правого слова — та сама межа, що в значення NavRow. */
private val ValueMax = 160.dp

/**
 * Рядок, що робить дію на місці або лише називає стан: праворуч слово, не
 * шеврон, — бо нікуди далі він не веде. Без [onClick] це не кнопка.
 */
@Composable
internal fun ActionRow(
    label: String,
    @DrawableRes icon: Int? = null,
    body: String? = null,
    action: String? = null,
    tone: RowTone = RowTone.Action,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    // Рядок, увесь напис якого і є дією («Сканувати ще раз»), говорить
    // бурштином сам; незворотне — червоним; решта — звичайним текстом.
    val ink = when {
        danger -> c.danger
        action.isNullOrBlank() && onClick != null -> c.accentText
        else -> c.text
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMin)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (ink == c.text) c.text2 else ink,
                modifier = Modifier.size(PhantomSize.iconM),
            )
            Spacer(Modifier.width(PhantomSpace.s5))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = PhantomText.body, color = ink)
            if (!body.isNullOrBlank()) {
                Text(text = body, style = PhantomText.footnote, color = c.text3)
            }
        }
        if (!action.isNullOrBlank()) {
            Spacer(Modifier.width(PhantomSpace.s3))
            Text(
                text = action,
                style = PhantomText.subhead,
                color = tone.color(),
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = ValueMax),
            )
        }
    }
}

/**
 * Системний дозвіл одним рядком: «Надано» зеленим — і тоді це не кнопка, або
 * «Надати» бурштином — і дотик веде на системний екран.
 */
@Composable
internal fun GrantRow(
    label: String,
    body: String? = null,
    granted: Boolean,
    grantedWord: String,
    grantWord: String,
    onGrant: () -> Unit,
) {
    ActionRow(
        label = label,
        body = body,
        action = if (granted) grantedWord else grantWord,
        tone = if (granted) RowTone.Done else RowTone.Action,
        onClick = if (granted) null else onGrant,
    )
}

/** Факт без дії: назва зліва, значення справа третім рівнем тексту. */
@Composable
internal fun InfoRow(label: String, value: String) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMin)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = PhantomText.body, color = c.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(PhantomSpace.s3))
        Text(
            text = value,
            style = PhantomText.callout,
            color = c.text3,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = ValueMax),
        )
    }
}

/** Заголовок усередині групи, коли підпис над групою вже зайнятий. */
@Composable
internal fun TextRow(label: String, body: String? = null) {
    val c = LocalPhantomColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
    ) {
        Text(text = label, style = PhantomText.body, color = c.text)
        if (!body.isNullOrBlank()) Text(text = body, style = PhantomText.footnote, color = c.text3)
    }
}

/**
 * Один варіант із кількох. Обраний має позначку справа; наслідок вибору —
 * [body] — варто показувати лише під обраним, інакше три речення поспіль
 * читаються як інструкція, а не як відповідь.
 */
@Composable
internal fun ChoiceRow(
    label: String,
    selected: Boolean,
    body: String? = null,
    /** Знак перед назвою (прапор мови) — для ока; TalkBack читає лише [label]. */
    leading: String? = null,
    onSelect: () -> Unit,
) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMin)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(selected)
        Spacer(Modifier.width(PhantomSpace.s5))
        if (leading != null) {
            Text(text = leading, style = PhantomText.title2, modifier = Modifier.clearAndSetSemantics { })
            Spacer(Modifier.width(PhantomSpace.s3))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = PhantomText.body, color = c.text)
            if (!body.isNullOrBlank()) Text(text = body, style = PhantomText.footnote, color = c.text3)
        }
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .size(PhantomSize.iconM)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(c.accent) else Modifier.border(2.dp, c.lineStrong, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painter = painterResource(DesignR.drawable.lc_check),
                contentDescription = null,
                tint = c.onAccent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
