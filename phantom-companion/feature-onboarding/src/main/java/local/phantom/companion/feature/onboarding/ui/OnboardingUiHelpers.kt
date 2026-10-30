package local.phantom.companion.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.component.PhantomMark
import local.phantom.companion.core.design.component.PhantomMarkFrame
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace

/**
 * Знак PHANTOM — той самий кадр, яким системна заставка віддає застосунок, тож
 * перший екран продовжує заставку, а не починає інший застосунок.
 *
 * Тут раніше пульсувала радіальна заграва на власному 8-секундному годиннику.
 * Перший екран продукту не має чого «дихати»: полотно стоїть, знак стоїть.
 */
@Composable
internal fun OnboardingMark(modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    PhantomMarkFrame(canvas = c.canvas, ink = c.text, modifier = modifier.size(MARK_BOX))
}

/** Коробка рівно під знак у масштабі заставки: 24 одиниці по [PhantomMark.SplashUnit]. */
private val MARK_BOX = PhantomMark.SplashUnit * PhantomMark.VIEWPORT

/** Ширина, на якій абзац першого запуску ще читається одним поглядом. */
internal val ReadingWidth = 340.dp

/**
 * One frame for every onboarding screen: the canvas, a content band and
 * bottom-pinned actions. Centring the band is what removes the dead canyon the
 * old per-screen `Spacer(weight(1f))` left above the buttons.
 */
@Composable
internal fun OnboardingScaffold(
    modifier: Modifier = Modifier,
    arrangement: Arrangement.Vertical = Arrangement.Center,
    /**
     * Чи розтягувати смугу вмісту на всю решту висоти.
     *
     * `true` (як було й лишається за замовчуванням) — смуга бере все, що є, і
     * центрує в собі короткий вміст. Наслідок заміряно на кроці імені, профіль
     * власника 420 dpi / шрифт 1,15: **порожнеча 152 dp між полем і «Далі»** і
     * ще 91 dp над карткою — половина полотна, зібрана у дві діри, і більша з
     * них розриває питання й відповідь на нього.
     *
     * `false` — смуга бере рівно стільки, скільки треба вмісту, дії сідають
     * одразу під ним, а залишок висоти збирається ВНИЗУ однією смугою, де
     * порожнеча читається як повітря. Довгий вміст поводиться як раніше: коли
     * він переростає доступну частку, смуга впирається в стелю і прокрутка
     * працює так само.
     *
     * **Чого цей параметр НЕ робить, і це головне:** він не чіпає `actions()`.
     * Кнопка лишається поза смугою прокрутки, тож `imePadding` нижче й далі
     * рятує її від клавіатури — та сама вада, яку лікував попередник
     * (див. коментар про «ряд дій під клавіатурою»). Правка, що прибирає
     * порожнечу ціною повернення тієї вади, — не правка.
     */
    contentFillsHeight: Boolean = true,
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            // Полотно — до самих країв вікна; відступи смуг ставляться вже всередині.
            .background(c.canvas)
            .systemBarsPadding()
            // Без цього ряд дій лишався ПІД клавіатурою: на першому ж екрані
            // людина вписувала імʼя й не бачила «Далі», доки не сховає ввід.
            .imePadding()
            .padding(horizontal = PhantomSpace.s6, vertical = PhantomSpace.s6),
    ) {
        Column(
            modifier = Modifier
                // fill = false: смуга бере стільки, скільки треба вмісту, а
                // не всю решту. Стеля лишається тією самою часткою, тож
                // довгий вміст упирається в неї і прокручується як раніше.
                .weight(1f, fill = contentFillsHeight)
                .fillMaxWidth()
                // Клавіатура забирає половину екрана: те, що не вміщається,
                // має прокручуватись, а не обрізатись.
                .verticalScroll(rememberScrollState()),
            verticalArrangement = if (contentFillsHeight) arrangement else Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        Spacer(modifier = Modifier.height(PhantomSpace.s5))
        actions()
    }
}

/**
 * Головна дія кроку — єдина бурштинова заливка на екрані (мова форми 1.2:
 * бурштин — це світло, а не фарба). Вимкнена дія не зникає, а відступає в
 * нейтральну поверхню, і напис лишається читабельним.
 */
@Composable
internal fun OnboardingPrimaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    Box(
        modifier = modifier
            .heightIn(min = PhantomSize.buttonL)
            .clip(CircleShape)
            .background(if (enabled) c.accent else c.overlay)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s6, vertical = PhantomSpace.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PhantomText.headline,
            color = if (enabled) c.onAccent else c.text3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Мітки в ресурсах написані малими (колишній капс-кікер); мова форми — речення. */
internal fun String.asSentence(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
