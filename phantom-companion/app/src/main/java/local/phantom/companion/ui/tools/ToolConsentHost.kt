package local.phantom.companion.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.R
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.tool.ToolRisk
import local.phantom.companion.core.tool.permission.Decision
import local.phantom.companion.core.tool.permission.ToolAsk
import local.phantom.companion.core.tool.permission.ToolConsentPrompter

internal data class ToolConsentModel(
    val toolId: String,
    val description: String,
    val outward: Boolean,
    val askEveryCall: Boolean,
    val headerFields: List<ToolAsk.Field>,
    val bodyFields: List<ToolAsk.Field>,
    val duplicateHeaderKinds: Set<ToolAsk.FieldKind> = emptySet(),
)

internal fun buildToolConsentModel(ask: ToolAsk): ToolConsentModel {
    val fields = ask.fields
    val headers = fields.filter { !it.isScrollableBody }
    val duplicateKinds = headers
        .groupingBy { it.kind }
        .eachCount()
        .filter { (kind, count) -> count > 1 && kind != ToolAsk.FieldKind.TARGET }
        .keys
    return ToolConsentModel(
        toolId = ask.toolId,
        description = ask.description,
        outward = ask.risk == ToolRisk.OUTWARD,
        askEveryCall = ask.askEveryCall,
        headerFields = headers,
        bodyFields = fields.filter { it.isScrollableBody },
        duplicateHeaderKinds = duplicateKinds,
    )
}

internal fun formatHeaderFieldLabel(
    baseLabel: String,
    field: ToolAsk.Field,
    model: ToolConsentModel,
): String = if (field.kind in model.duplicateHeaderKinds) {
    "$baseLabel (${field.key})"
} else {
    baseLabel
}

/**
 * Єдиний читач [ToolConsentPrompter.pending]. Живе над усім графом, бо виклик
 * інструмента приходить і зі Стрічки, і з веб-месенджера — питання мусить
 * наздогнати оператора там, де він є.
 *
 * Типізовані поля (отримувач, сума, адреса, метод) стоять у фіксованих
 * позиціях угорі й ніколи не ховаються за довгим тілом; повне тіло прокручується
 * без обрізання трикрапкою.
 */
@Composable
fun ToolConsentHost(prompter: ToolConsentPrompter) {
    val pending by prompter.pending.collectAsStateWithLifecycle()
    val ask = pending ?: return
    val model = remember(ask.ask) { buildToolConsentModel(ask.ask) }
    val c = LocalPhantomColors.current

    Dialog(onDismissRequest = { ask.answerWith(Decision.ASK) }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PhantomRadius.sheet))
                .background(c.raised)
                .padding(PhantomSpace.s6),
        ) {
            Text(
                text = stringResource(R.string.tool_consent_title),
                style = PhantomText.title2,
                color = c.text,
            )
            Spacer(Modifier.height(PhantomSpace.s1))
            Text(
                text = if (model.outward) {
                    stringResource(R.string.tool_consent_risk_outward)
                } else {
                    stringResource(R.string.tool_consent_risk_device)
                },
                style = PhantomText.footnote,
                fontWeight = FontWeight.SemiBold,
                color = if (model.outward) c.danger else c.warning,
            )
            Spacer(Modifier.height(PhantomSpace.s3))
            Text(text = model.description, style = PhantomText.body, color = c.text)

            if (model.headerFields.isNotEmpty()) {
                Spacer(Modifier.height(PhantomSpace.s3))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PhantomRadius.control))
                        .background(c.overlay)
                        .padding(horizontal = PhantomSpace.s3, vertical = PhantomSpace.s2)
                        .testTag("consent_header_fields"),
                ) {
                    model.headerFields.forEachIndexed { index, field ->
                        if (index > 0) Spacer(Modifier.height(PhantomSpace.s2))
                        Text(
                            text = formatHeaderFieldLabel(fieldLabel(field), field, model),
                            style = PhantomText.caption,
                            color = c.text3,
                        )
                        Text(
                            text = field.value,
                            style = PhantomText.body,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                            modifier = Modifier.testTag("consent_field_${field.kind.name.lowercase()}"),
                        )
                    }
                }
            }

            if (model.bodyFields.isNotEmpty()) {
                Spacer(Modifier.height(PhantomSpace.s2))
                val bodyScroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(PhantomRadius.control))
                        .background(c.overlay)
                        .verticalScroll(bodyScroll)
                        .padding(horizontal = PhantomSpace.s3, vertical = PhantomSpace.s2)
                        .testTag("consent_body_scroll"),
                ) {
                    model.bodyFields.forEachIndexed { index, field ->
                        if (index > 0) Spacer(Modifier.height(PhantomSpace.s2))
                        Text(
                            text = stringResource(R.string.tool_consent_field_body),
                            style = PhantomText.caption,
                            color = c.text3,
                        )
                        Text(
                            text = field.value,
                            style = PhantomText.footnote,
                            color = c.text2,
                            modifier = Modifier.testTag("consent_field_body"),
                        )
                    }
                }
            }

            Spacer(Modifier.height(PhantomSpace.s5))
            Answer(
                label = stringResource(R.string.tool_consent_once),
                fill = c.accent,
                ink = c.onAccent,
                onClick = { ask.answerWith(Decision.ONCE) },
            )
            if (!model.askEveryCall) {
                Spacer(Modifier.height(PhantomSpace.s2))
                Answer(
                    label = stringResource(R.string.tool_consent_always),
                    fill = c.overlay,
                    ink = c.text,
                    onClick = { ask.answerWith(Decision.ALWAYS) },
                )
            }
            Spacer(Modifier.height(PhantomSpace.s2))
            Answer(
                label = stringResource(R.string.tool_consent_never),
                fill = Color.Transparent,
                ink = c.text2,
                onClick = { ask.answerWith(Decision.NEVER) },
            )
            Spacer(Modifier.height(PhantomSpace.s3))
            Text(
                text = stringResource(R.string.tool_consent_hint),
                style = PhantomText.footnote,
                color = c.text3,
            )
        }
    }
}

@Composable
private fun fieldLabel(field: ToolAsk.Field): String = when (field.kind) {
    ToolAsk.FieldKind.RECIPIENT -> stringResource(R.string.tool_consent_field_recipient)
    ToolAsk.FieldKind.AMOUNT -> stringResource(R.string.tool_consent_field_amount)
    ToolAsk.FieldKind.URL -> stringResource(R.string.tool_consent_field_url)
    ToolAsk.FieldKind.METHOD -> stringResource(R.string.tool_consent_field_method)
    ToolAsk.FieldKind.TARGET -> stringResource(R.string.tool_consent_field_target, field.key)
    ToolAsk.FieldKind.BODY -> stringResource(R.string.tool_consent_field_body)
}

@Composable
private fun Answer(label: String, fill: Color, ink: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomSize.buttonL)
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = PhantomText.headline, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
