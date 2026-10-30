package local.phantom.companion.feature.stream.ui.report

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomMono
import local.phantom.companion.core.design.theme.PhantomSans
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomThemeSpec

/**
 * Редакційний голос звіту: не друга шкала, а ІМЕНА документа на кроках
 * продукту. Розміри беруться рівно з `PhantomTextRoles` — 26 (Display),
 * 16 (Prose), 13 (Body), 11 (Caption), 10 (Kicker) — плюс один крок 20,
 * якого в діалозі немає, бо між титулом і тілом документа є розділ.
 *
 * Було двадцять пʼять чисел, зокрема 9.5 і 11.5 sp, яких немає більше ніде
 * в продукті; звіт через це читався як чужий застосунок і мітки осей не
 * читались за кермом узагалі.
 *
 * Цифри моноширинні з `tnum`: у колонці чисел пропорційний шрифт зсуває
 * розряди, і таблицю доводиться читати по одному значенню.
 */
object ReportType {

    private val tabular = TextStyle(fontFeatureSettings = "tnum")

    /** Титул документа. Один на весь звіт. */
    val hero = TextStyle(
        fontFamily = PhantomSans,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.6).sp,
    )

    /** Назва в картці-передачі та в липкій шапці. */
    val title = TextStyle(
        fontFamily = PhantomSans,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
    )

    /** Заголовок розділу. */
    val sectionTitle = TextStyle(
        fontFamily = PhantomSans,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
    )

    /** Заголовок діаграми/таблиці всередині розділу. */
    val blockTitle = TextStyle(
        fontFamily = PhantomSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** Ліда — те саме тіло, але веде; різницю несе вага й колір, не розмір. */
    val lede = TextStyle(
        fontFamily = PhantomSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )

    /** Основне тіло документа. Проза читається довго — крок Prose. */
    val body = TextStyle(
        fontFamily = PhantomSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )

    /** Мікрозаголовок: рубрика, нумерація розділу, підпис групи. */
    val kicker = TextStyle(
        fontFamily = PhantomMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 2.sp,
    )

    /** Підпис під діаграмою, виноска, застереження. */
    val caption = TextStyle(
        fontFamily = PhantomSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )

    /** Мітки осей і легенд. Найдрібніше в документі — і воно мусить читатись. */
    val axis = TextStyle(
        fontFamily = PhantomMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )

    /** Головне число розділу. */
    val statHero = tabular.copy(
        fontFamily = PhantomMono,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.8).sp,
    )

    /** Число в сітці показників. */
    val stat = tabular.copy(
        fontFamily = PhantomMono,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.4).sp,
    )

    /** Число в таблиці, дельта, час у хронології. */
    val statSmall = tabular.copy(
        fontFamily = PhantomMono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** Комірка таблиці (текстова). */
    val cell = TextStyle(
        fontFamily = PhantomSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** Цитата/вердикт — велика фраза, що закриває розділ. */
    val quote = TextStyle(
        fontFamily = PhantomSans,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    )
}

/**
 * Метрики документа. Усе — кратне 8-піксельній сітці [PhantomSpacings];
 * тут лише імена для ролей, а не нові числа.
 */
object ReportMetrics {
    /** Поле сторінки. Документ читається вужчим за екран — це не картка. */
    val pageEdge: Dp = PhantomSpacings.Large
    /** Відступ між блоками всередині розділу. */
    val blockGap: Dp = PhantomSpacings.Large
    /** Відступ перед новим розділом — удвічі більший, ніж між блоками. */
    val sectionGap: Dp = PhantomSpacings.XXLarge
    /** Внутрішнє поле панелей (діаграм, таблиць). */
    val panelPad: Dp = PhantomSpacings.Base
    /** Висота полотна звичайної діаграми. */
    val chartHeight: Dp = 160.dp
    /** Висота полотна «доказового» кадру (слід, розбіжність). */
    val mapHeight: Dp = 208.dp
    /** Липка шапка: два рядки (назва 16/24 + підпис 11/15) мусять уміститись
     *  і при масштабі тексту 1.5 — на 56 dp вони обрізались. */
    val headerHeight: Dp = 64.dp
    /** Ширина висувної рейки змісту. */
    val railWidth: Dp = 268.dp
    /** Стеля рейки: далі вона перестає бути накладкою і стає екраном. */
    val railMaxHeight: Dp = 520.dp
    /** Товщина смуги прогресу читання. */
    val progressBar: Dp = 2.dp
}

/**
 * Кольори звіту. Семантика ([ReportTone]) свідомо відвʼязана від акценту
 * бренду: бурштин у PHANTOM означає «система», а не «увага». Коли обидва
 * сенси несе один колір, тривога перестає читатись як тривога.
 */
fun toneColorOf(theme: PhantomThemeSpec, tone: ReportTone): Color = when (tone) {
    ReportTone.NEUTRAL -> theme.inkSecondary
    ReportTone.GOOD -> theme.success
    ReportTone.WARN -> theme.primary
    ReportTone.CRIT -> theme.coral
    ReportTone.ACCENT -> theme.accentSharp
}

/**
 * Композабельна зручність. Свідомо тонка обгортка над [toneColorOf]:
 * усередині `DrawScope` композабельної версії не існує, а полотнам колір
 * потрібен так само, як і тексту — тож джерело правди має бути звичайною
 * функцією, а не композиційним читанням.
 */
@Composable
@ReadOnlyComposable
fun toneColor(tone: ReportTone): Color = toneColorOf(LocalPhantomTheme.current, tone)

/** Колір ряду з урахуванням явного тону; нейтральний бере палітру за індексом. */
fun seriesColorFor(theme: PhantomThemeSpec, tone: ReportTone?, index: Int): Color =
    if (tone == null || tone == ReportTone.NEUTRAL) seriesColor(theme, index) else toneColorOf(theme, tone)

/** Колір позначки/події: нейтральна лишається тихою, решта — семантичною. */
fun markerColorOf(theme: PhantomThemeSpec, tone: ReportTone): Color =
    if (tone == ReportTone.NEUTRAL) theme.inkSecondary else toneColorOf(theme, tone)

/**
 * Категоріальна палітра для рядів діаграм — виведена з токенів теми, а не
 * підібрана на око. Сім відтінків: далі людське око все одно перестає
 * зіставляти легенду з кривою, і потрібна не восьма фарба, а інша діаграма.
 */
fun seriesPalette(theme: PhantomThemeSpec): List<Color> = listOf(
    theme.primary,
    theme.accentSharp,
    theme.success,
    theme.coral,
    theme.primaryDeep,
    theme.primarySoft,
    theme.inkSecondary,
)

/** Колір ряду за індексом — циклічно, щоб восьмий ряд не став прозорим. */
fun seriesColor(theme: PhantomThemeSpec, index: Int): Color {
    val palette = seriesPalette(theme)
    return palette[((index % palette.size) + palette.size) % palette.size]
}

/** Колір смуги швидкості: у межах, біля межі, понад межу. */
fun speedBandColor(theme: PhantomThemeSpec, band: ReportAggregate.SpeedBand): Color = when (band) {
    ReportAggregate.SpeedBand.UNDER -> theme.success
    ReportAggregate.SpeedBand.NEAR -> theme.primary
    ReportAggregate.SpeedBand.OVER -> theme.coral
}

/** Гліф родини звіту. Текстовий, бо картка-передача говорить мовою фасетів. */
fun kindGlyph(kind: ReportKind): String = when (kind) {
    ReportKind.TRIP -> "→"
    ReportKind.PERIOD -> "∑"
    ReportKind.FUEL -> "◈"
    ReportKind.VEHICLE -> "⚙"
    ReportKind.INCIDENT -> "⚠"
    ReportKind.SAFETY -> "⌖"
    ReportKind.MOTION -> "∿"
    ReportKind.AGENT -> "⟐"
    ReportKind.VOICE -> "◑"
    ReportKind.PLACES -> "◇"
    ReportKind.GENERIC -> "§"
}
