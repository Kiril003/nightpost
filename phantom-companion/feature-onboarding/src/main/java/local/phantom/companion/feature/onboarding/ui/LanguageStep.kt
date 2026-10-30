package local.phantom.companion.feature.onboarding.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.design.component.PhantomMark
import local.phantom.companion.core.design.component.PhantomMarkFrame
import local.phantom.companion.core.design.motion.MotionTokens
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.onboarding.R

/** Один рядок переліку мов: що показати й чи він обраний. */
data class LanguageRow(
    val language: AppLanguage,
    val flag: String,
    val nativeName: String,
    val selected: Boolean,
)

/**
 * Перелік першого екрана: англійська перша й уже обрана, далі решта мов, які
 * застосунок справді має ([AppLanguage.offered]); назва — мовою самої мови.
 */
fun languageRows(selected: AppLanguage = AppLanguage.DEFAULT): List<LanguageRow> =
    AppLanguage.offered.map { LanguageRow(it, it.flag, it.nativeName, it == selected) }

/**
 * Рух першого екрана — один годинник на 400 мс, кожен елемент сходить на
 * свій відрізок: знак першим, далі рядок, мови, дія. Лише прозорість і зсув
 * шару, без зміни розкладки; з вимкненою анімацією системи — одразу на місці.
 */
object FirstRunMotion {
    const val BUDGET_MS = 400
    const val RISE_MS = 280
    const val MARK_AT = 0
    const val LINE_AT = 40
    const val ACTION_AT = BUDGET_MS - RISE_MS
    private const val ROWS_AT = 70
    private const val ROW_STEP = 16

    /** Коли рушає рядок мови [index]; хвіст переліку сідає разом з останнім. */
    fun rowAt(index: Int): Int = (ROWS_AT + index * ROW_STEP).coerceAtMost(BUDGET_MS - RISE_MS)

    /** 0 → 1 для елемента, що рушає на [atMs]; згасання швидкості (кубічне). */
    fun progress(clockMs: Float, atMs: Int): Float {
        val t = ((clockMs - atMs) / RISE_MS).coerceIn(0f, 1f)
        val left = 1f - t
        return 1f - left * left * left
    }

    /** Скільки йде годинник: 0 — анімацію в системі вимкнено, екран одразу готовий. */
    fun clockMs(motionScale: Float): Int = if (motionScale <= 0f) 0 else BUDGET_MS
}

private val RISE: Dp = 12.dp
private const val MARK_FROM = 0.94f

/**
 * Перший екран Nightpost: знак, один рядок про те, що це, і мова.
 *
 * Дотик до мови лише позначає її; далі веде «Continue» з позначеною.
 * Слова екрана — мовою обраного рядка, а не системи: англійська обрана, отже
 * й екран англійською, хоч би що казав телефон.
 */
@Composable
fun LanguageStep(
    onPick: (AppLanguage) -> Unit,
    selected: AppLanguage = AppLanguage.DEFAULT,
) {
    val c = LocalPhantomColors.current
    val context = LocalContext.current
    var chosen by rememberSaveable(selected) { mutableStateOf(selected) }
    val words = remember(context, chosen) { AppLanguage.localized(context, chosen) }
    val rows = remember(chosen) { languageRows(chosen) }
    var done by remember { mutableStateOf(false) }
    val pick: (AppLanguage) -> Unit = { language ->
        if (!done) {
            done = true
            onPick(language)
        }
    }

    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        val run = FirstRunMotion.clockMs(scale)
        if (run == 0) {
            clock.snapTo(FirstRunMotion.BUDGET_MS.toFloat())
        } else {
            clock.animateTo(FirstRunMotion.BUDGET_MS.toFloat(), MotionTokens.clockLinear(run))
        }
    }
    val now = { clock.value }

    OnboardingScaffold(
        contentFillsHeight = false,
        actions = {
            OnboardingPrimaryButton(
                label = words.getString(R.string.onb_lang_continue),
                // Лишається світлом і після дотику: сірий спалах перед переходом
                // читався б як помилка. Повтор відсікає [done].
                enabled = true,
                onClick = { pick(chosen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .rise(now, FirstRunMotion.ACTION_AT),
            )
        },
    ) {
        Spacer(Modifier.height(PhantomSpace.s4))
        PhantomMarkFrame(
            canvas = c.canvas,
            ink = c.accentLine,
            modifier = Modifier
                .size(PhantomMark.SplashUnit * PhantomMark.VIEWPORT)
                .settle(now),
        )
        Spacer(Modifier.height(PhantomSpace.s5))
        Text(
            text = words.getString(R.string.onb_lang_mark),
            style = PhantomText.display,
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .semantics { heading() }
                .rise(now, FirstRunMotion.LINE_AT),
        )
        Spacer(Modifier.height(PhantomSpace.s2))
        Text(
            text = words.getString(R.string.onb_lang_line),
            style = PhantomText.callout,
            color = c.text2,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = ReadingWidth)
                .rise(now, FirstRunMotion.LINE_AT),
        )
        Spacer(Modifier.height(PhantomSpace.s10))
        Text(
            text = words.getString(R.string.onb_lang_title),
            style = PhantomText.footnote,
            fontWeight = FontWeight.SemiBold,
            color = c.text2,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() }
                .rise(now, FirstRunMotion.rowAt(0)),
        )
        Spacer(Modifier.height(PhantomSpace.s2))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            rows.forEachIndexed { index, row ->
                LanguageRowItem(
                    row = row,
                    enabled = !done,
                    onClick = { if (!done) chosen = row.language },
                    modifier = Modifier.rise(now, FirstRunMotion.rowAt(index)),
                )
            }
        }
    }
}

/** Рядок мови: прапор (для ока, не для TalkBack), власна назва, позначка вибору. */
@Composable
private fun LanguageRowItem(
    row: LanguageRow,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.card)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (row.selected) c.accentSoft else c.surface)
            .border(if (row.selected) 1.5.dp else 1.dp, if (row.selected) c.accentLine else c.line, shape)
            .selectable(selected = row.selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.flag,
            fontSize = 24.sp,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Spacer(Modifier.width(PhantomSpace.s3))
        Text(
            text = row.nativeName,
            style = PhantomText.headline,
            color = c.text,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(PhantomSpace.s3))
        RadioDot(selected = row.selected)
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) c.accent else c.lineStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(c.accent),
            )
        }
    }
}

/** Схід елемента: з прозорості й трохи знизу — лише шар, розкладка стоїть. */
private fun Modifier.rise(clock: () -> Float, atMs: Int): Modifier = graphicsLayer {
    alpha = FirstRunMotion.progress(clock(), atMs)
    translationY = (1f - FirstRunMotion.progress(clock(), atMs)) * RISE.toPx()
}

/** Знак продовжує заставку: проявляється й ледь доростає до свого розміру. */
private fun Modifier.settle(clock: () -> Float): Modifier = graphicsLayer {
    alpha = FirstRunMotion.progress(clock(), FirstRunMotion.MARK_AT)
    scaleX = MARK_FROM + (1f - MARK_FROM) * FirstRunMotion.progress(clock(), FirstRunMotion.MARK_AT)
    scaleY = MARK_FROM + (1f - MARK_FROM) * FirstRunMotion.progress(clock(), FirstRunMotion.MARK_AT)
}
