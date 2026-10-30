package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.layout.bottomChromeInsets
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.core.design.R as DesignR

/**
 * Відповідь PHANTOM, що лишається НА МАПІ.
 *
 * Раніше питання з пошуку вело в розмову: людина питала про те, на що
 * дивиться, і втрачала те, на що дивилась. Тут відповідь приходить сюди, мапа
 * лишається позаду видимою, а розмова — не пункт призначення, а ОДНА З ДВОХ
 * дій, і обидві обирає людина, а не застосунок.
 *
 * Висота обмежена половиною екрана свідомо: аркуш, який заповнює екран, нічим
 * не кращий за перехід на інший екран.
 *
 * 03.10 — аркуш паперу, як відповідь PHANTOM у розмові (identity.css):
 * `surface`, волосина `line`, усі кути 18, поле 12/14, а над текстом — знак
 * і слово «PHANTOM». Зерна паперу, як на вебі, тут нема: нативного токена
 * для нього ще не існує (дописано в нотатки як наступний крок).
 */
@Composable
internal fun MapAnswerSheet(
    answer: MapAnswer,
    onClose: () -> Unit,
    onShowOnMap: (String) -> Unit,
    onContinueInChat: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!answer.isLive) return
    val c = LocalPhantomColors.current
    val maxBody: Dp = (LocalConfiguration.current.screenHeightDp * BODY_SCREEN_FRACTION).dp
    val paper = RoundedCornerShape(PhantomRadius.bubble)

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Один інсет на все: смуга навігації, резерв острівця і клавіатура
            // беруться поосьовим максимумом, а не сумою. Голий
            // `navigationBarsPadding` лишав би останній рядок під островом.
            .windowInsetsPadding(bottomChromeInsets())
            .padding(PhantomSpace.s2)
            .clip(paper)
            .background(c.surface)
            .border(HAIRLINE, c.line, paper)
            .padding(start = 14.dp, end = PhantomSpace.s1, top = PhantomSpace.s1, bottom = PhantomSpace.s3),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        // ── Хто говорить (знак і слово, як підпис у розмові), у якому він
        //    стані, і як це закрити.
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhantomMarkGlyph(tint = c.text, modifier = Modifier.size(MARK))
            Spacer(Modifier.width(6.dp))
            SheetText(
                stringResource(R.string.map_ask_speaker),
                PhantomText.footnote,
                color = c.text,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (answer.isBusy) {
                Spacer(Modifier.width(PhantomSpace.s2))
                // Слово, а не кільце: воно називає стан і зупиняється разом із
                // потоком, а кільце крутиться однаково і поки думають, і поки
                // все давно зламалось.
                SheetNote(stringResource(R.string.map_ask_thinking), maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            val closeLabel = stringResource(R.string.map_ask_close)
            Box(
                modifier = Modifier
                    .size(PhantomTouchFloor)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = closeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(DesignR.drawable.lc_close),
                    contentDescription = null,
                    tint = c.text2,
                    modifier = Modifier.size(PhantomSize.iconM),
                )
            }
        }

        Column(
            modifier = Modifier.padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            // ── Про що спитали. Рівно те, що набрала людина — щоб було видно,
            //    на що саме прийшла ця відповідь.
            SheetNote(answer.question, maxLines = QUESTION_LINES)

            // ── Сама відповідь. Тече потоком, тож коробка вже має свою стелю:
            //    інакше аркуш стрибав би на кожному токені.
            if (answer.hasText) {
                Column(
                    modifier = Modifier
                        .heightIn(max = maxBody)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SheetText(answer.text, PhantomText.body, color = c.text)
                }
            }

            // ── Невдача каже, ЩО сталося, і дає рівно одну дію.
            answer.error?.let { text ->
                SheetText(text, PhantomText.body, color = c.text2)
            }

            // ── Дії. Розмова тут — вибір, а не пункт призначення.
            Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2)) {
                when {
                    answer.phase == MapAnswer.Phase.FAILED ->
                        QuietAction(stringResource(R.string.map_ask_retry), onRetry)

                    answer.isBusy ->
                        QuietAction(stringResource(R.string.map_ask_stop), onClose)

                    else -> {
                        QuietAction(stringResource(R.string.map_ask_show)) { onShowOnMap(answer.question) }
                        QuietAction(stringResource(R.string.map_ask_continue)) {
                            onContinueInChat(answer.question)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Дія без заливки. На цьому аркуші немає головної дії: показати на мапі й
 * піти в розмову — рівноцінні, і виділити одну означало б вирішити за людину.
 */
@Composable
private fun QuietAction(label: String, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .heightIn(min = PhantomTouchFloor)
            .clip(CircleShape)
            .border(HAIRLINE, c.lineStrong, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = PhantomSpace.s4),
        contentAlignment = Alignment.Center,
    ) {
        SheetText(label, PhantomText.subhead, color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private val HAIRLINE = 1.dp
private val MARK = 14.dp
private const val BODY_SCREEN_FRACTION = 0.42f
private const val QUESTION_LINES = 2
