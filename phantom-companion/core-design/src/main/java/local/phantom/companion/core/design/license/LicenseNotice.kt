package local.phantom.companion.core.design.license

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.R
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace

/**
 * Пояснення на місці самої здатності — там, де стояв би її вимикач.
 *
 * Ці композабли навмисно НЕ мають шаблонного тексту: [headline] і [body]
 * приходять готовими реченнями з модуля, якому належить екран. Підставляти
 * назву функції у спільний каркас не можна — українська відмінює й узгоджує
 * рід, і один шаблон на пʼять функцій дає чотири криві речення.
 *
 * Проба відкриває платне на 14 днів. Тому мовчазне згасання — найгірший
 * можливий варіант: людина, яка на пʼятнадцятий день бачить, що голос погіршав
 * і мапа сплющилась, без слів читає це як поламку і починає сумніватися й у
 * тривогах. Кожен замок мусить уміти сказати «не буду», а не «не можу».
 */
@Composable
fun LicenseNotice(
    headline: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val entitlements = LocalEntitlements.current

    // Мова форми 1.2: піднята картка без скла й рамки, заголовок headline,
    // пояснення footnote, дія — бурштинова капсула (мʼяка заливка, бо головна
    // дія екрана — не тут, а в самій здатності).
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.card))
            .background(c.raised)
            .padding(PhantomSpace.s4),
    ) {
        Text(text = headline, style = PhantomText.headline, color = c.text)
        Spacer(modifier = Modifier.height(PhantomSpace.s1))
        Text(text = body, style = PhantomText.footnote, color = c.text2)
        Spacer(modifier = Modifier.height(PhantomSpace.s3))
        Box(
            modifier = Modifier
                .heightIn(min = PhantomSize.buttonM)
                .clip(CircleShape)
                .background(c.accentSoft)
                .clickable(role = Role.Button, onClick = entitlements::openLicense)
                .padding(horizontal = PhantomSpace.s4),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.license_gate_open),
                style = PhantomText.subhead,
                fontWeight = FontWeight.SemiBold,
                color = c.accentText,
            )
        }
    }
}

/** Той самий зміст в один рядок — де на блок немає місця (рейки, деки). */
@Composable
fun LicenseNoticeLine(
    text: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val entitlements = LocalEntitlements.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PhantomSpace.touchMinAndroid)
            .clickable(role = Role.Button, onClick = entitlements::openLicense)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = PhantomText.footnote,
            color = c.text2,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(PhantomSpace.s2))
        Text(
            text = stringResource(R.string.license_gate_open),
            style = PhantomText.footnote,
            fontWeight = FontWeight.SemiBold,
            color = c.accentText,
        )
    }
}

/**
 * «проба · N дн.» біля самої здатності. Побачити, що зникне, треба ДО того, як
 * воно зникло, — інакше про пробу людина дізнається з її кінця.
 */
@Composable
fun TrialBadge(daysLeft: Int, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    Text(
        text = stringResource(R.string.license_gate_trial, daysLeft).replaceFirstChar { it.titlecase() },
        style = PhantomText.caption.copy(fontFeatureSettings = "tnum"),
        color = c.accentText,
        modifier = modifier
            .clip(RoundedCornerShape(PhantomRadius.chip))
            .background(c.accentSoft)
            .padding(horizontal = PhantomSpace.s2, vertical = 2.dp),
    )
}
