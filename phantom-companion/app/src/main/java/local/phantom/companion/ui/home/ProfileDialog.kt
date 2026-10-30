package local.phantom.companion.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import local.phantom.companion.R
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace

/**
 * Діалог Профілю на токенах v1.2: піднята поверхня, заголовок title2, бурштин
 * лише на кнопці дії, червоне — лише на незворотному.
 *
 * Тимчасовий. Спільний PhantomDialog їде пакетом native-theme-bridge і на цій
 * базі його ще немає; щойно приїде — виклики переходять на нього, а цей файл
 * зникає. До того стоковий AlertDialog Material тут заборонений: він малює
 * власну палітру, і діалог виглядав би гостем зі старого застосунку.
 */
@Composable
internal fun ProfileDialog(
    title: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
    danger: Boolean = false,
    confirmEnabled: Boolean = true,
    secondary: Pair<String, () -> Unit>? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = LocalPhantomColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PhantomRadius.sheet))
                .background(c.raised)
                .padding(PhantomSpace.s5),
        ) {
            Text(text = title, style = PhantomText.title2, color = c.text)
            if (!body.isNullOrBlank()) {
                Spacer(Modifier.height(PhantomSpace.s2))
                Text(text = body, style = PhantomText.callout, color = c.text2)
            }
            if (content != null) {
                Spacer(Modifier.height(PhantomSpace.s4))
                content()
            }
            Spacer(Modifier.height(PhantomSpace.s4))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (second, onSecond) = secondary ?: (stringResource(R.string.action_cancel) to onDismiss)
                DialogButton(second, c.text2, enabled = true, onClick = onSecond)
                Spacer(Modifier.width(PhantomSpace.s2))
                DialogButton(
                    label = confirm,
                    color = if (danger) c.danger else c.accentText,
                    enabled = confirmEnabled,
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .heightIn(min = PhantomSpace.touchMinAndroid)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = PhantomText.headline, color = if (enabled) color else c.text3)
    }
}

/**
 * Поле вводу в діалозі: оверлей замість рамки Material, курсор бурштином,
 * підказка третім рівнем тексту. Підказку читає й TalkBack — у полі без
 * підпису вона єдине, що каже, що сюди вписати.
 */
@Composable
internal fun ProfileField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = PhantomText.body.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = hint },
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhantomSpace.touchMinAndroid)
                    .clip(RoundedCornerShape(PhantomRadius.control))
                    .background(c.overlay)
                    .padding(horizontal = PhantomSpace.s3, vertical = PhantomSpace.s3),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(text = hint, style = PhantomText.body, color = c.text3)
                inner()
            }
        },
    )
}
