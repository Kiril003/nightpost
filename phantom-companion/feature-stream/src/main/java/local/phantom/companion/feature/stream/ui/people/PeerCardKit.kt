package local.phantom.companion.feature.stream.ui.people

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace

/** Плоска піднята група рядків на полотні — та сама, що в «Профілі». */
@Composable
internal fun CardGroup(
    caption: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (caption != null) {
            Text(
                text = caption.sentence(),
                style = PhantomText.footnote,
                color = c.text3,
                modifier = Modifier.padding(start = PhantomSpace.s4, bottom = PhantomSpace.s2),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().clip(GroupShape).background(c.raised),
            content = content,
        )
    }
}

@Composable
internal fun GroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = PhantomSpace.s4)
            .height(1.dp)
            .background(LocalPhantomColors.current.line),
    )
}

internal enum class RowTone { Action, Danger, Plain }

@Composable
internal fun RowTone.ink(): Color {
    val c = LocalPhantomColors.current
    return when (this) {
        RowTone.Action -> c.accentText
        RowTone.Danger -> c.danger
        RowTone.Plain -> c.text
    }
}

/** Рядок-дія групи: усе тіло — ціль дотику. Бурштин — лише на діях. */
@Composable
internal fun CardRow(
    label: String,
    @DrawableRes icon: Int?,
    tone: RowTone,
    onClick: () -> Unit,
    body: String? = null,
) {
    val c = LocalPhantomColors.current
    val ink = tone.ink()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (tone == RowTone.Plain) c.text2 else ink,
                modifier = Modifier.size(PhantomSize.iconM),
            )
            Spacer(Modifier.width(PhantomSpace.s4))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label.sentence(), style = PhantomText.body, color = ink)
            if (body != null) Text(text = body.sentence(), style = PhantomText.footnote, color = c.text3)
        }
    }
}

/** Рядок, що лише каже: назва ліворуч, значення праворуч. */
@Composable
internal fun ValueRow(label: String, value: String) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label.sentence(), style = PhantomText.body, color = c.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(PhantomSpace.s2))
        Text(text = value, style = PhantomText.callout.copy(fontFeatureSettings = "tnum"), color = c.text2)
    }
}

/** Суцільна кнопка: бурштин для очікуваного виходу, червоне — для незворотного. */
@Composable
internal fun FilledButton(
    label: String,
    fill: Color,
    ink: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    Row(
        modifier = modifier
            .heightIn(min = PhantomSize.buttonL)
            .clip(ButtonShape)
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s5),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(PhantomSize.iconM),
            )
            Spacer(Modifier.width(PhantomSpace.s2))
        }
        Text(text = label.sentence(), style = PhantomText.headline, color = ink)
    }
}

/** Тиха кнопка без підкладки: друга, рідша відповідь поруч із головною. */
@Composable
internal fun QuietButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
) {
    val c = LocalPhantomColors.current
    Box(
        modifier = modifier
            .heightIn(min = PhantomSize.buttonL)
            .clip(ButtonShape)
            .then(if (outlined) Modifier.border(1.dp, c.lineStrong, ButtonShape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label.sentence(), style = PhantomText.headline, color = color)
    }
}

/**
 * Рядок, що його набрали з малої, на початку речення — з великої.
 *
 * Слова картки в `strings.xml`/`strings_peercard.xml` писались під старий
 * образ — малою, як підписи-шепіт. v1.2 пише реченням; ресурси цього пакета не
 * мої, тож велику літеру ставить рендер. Коли власник рядків перепише їх —
 * виклик стане холостим, а не хибним.
 */
internal fun String.sentence(): String = replaceFirstChar { it.uppercase() }

private val GroupShape = RoundedCornerShape(PhantomRadius.sheet)
private val ButtonShape = RoundedCornerShape(PhantomRadius.control)
private val ROW_MIN = 56.dp
