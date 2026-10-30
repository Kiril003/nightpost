package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R

// Мірки хрому мапи. Вигляд — у MapCardParts.kt (токени v1.2); тут лишились
// числа, на які спираються сусіди (смуга праворуч, пігулки довіри й шерингу),
// компас і платівка приладу.

internal object MapChromeMetrics {
    val Control: Dp = 48.dp
    val Glyph: Dp = 20.dp
    val GlyphSmall: Dp = 16.dp
    val Hairline: Dp = 1.dp
    val Edge: Dp = 12.dp

    /**
     * Ширина непрозорої смуги праворуч — дека плюс поле з обох боків.
     *
     * Це єдине число, яким мапа знає про хром. Раніше його не знав ніхто: вал
     * вписування маршруту брав 32dp «на око» (менше за саму деку, тож хвіст
     * маршруту лягав під кнопки), а движок підписів не знав нічого взагалі.
     * Міняється дека — міняється і те, скільки скла лишається мапі.
     */
    val RailBand: Dp = Control + Edge * 2

    /** Was 0.9f, "thinner than the app's own glass" — but on the device the
     *  shop labels («Megasport», «JYSK») and two POI discs read straight
     *  through the layers and zoom decks, so the chrome had map in it and the
     *  map had chrome in it. A control is opaque; the world goes around it. */
    const val PANEL_ALPHA: Float = 0.97f

    /**
     * The one drawn height for pills and ordinary actions, tapped or not.
     *
     * 48 is the law of the FINGER, not of the form: a lone pill reaches it
     * through `minimumInteractiveComponentSize()`, which grows the target
     * without growing the glass. Reading 48 as a drawn height is what made the
     * status pill, the deck slot and the «старт» hero all one calibre — a
     * screen with no size hierarchy, because there was no size scale.
     */
    val Ribbon: Dp = 34.dp

    /** Поле пігулки, не картки. Base(16) навколо 13sp тексту робить скло вдвічі
     *  більшим за слово, яке воно несе. */
    val PillPadH: Dp = 12.dp
}

/**
 * Компас — та сама піднята кругла поверхня з тінню, що й чипи дій унизу
 * ([MapActionChip]), лише без слова: його назва — у `contentDescription`, а
 * сам він зʼявляється тільки тоді, коли мапу повернуто.
 */
@Composable
internal fun MapControlOrb(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    Box(
        modifier = modifier
            .size(MapChromeMetrics.Control)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .background(c.raised)
            .border(1.dp, c.line, RoundedCornerShape(PhantomRadius.control))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = c.text,
            modifier = Modifier.size(PhantomSize.iconM),
        )
    }
}

/**
 * Прилад мапи: скільки це на землі, з чого ця земля відома, скільки лишилось
 * світла і на скільки бреше стрілка компаса.
 *
 * Раніше це були ТРИ скла в один стовпчик — платівка масштабу (Large),
 * пігулка схилення (Pill) і скло світла (Medium), — тобто три радіуси на три
 * сусідні довідкові рядки. Роль у них одна («прилад»), отже скло теж одне:
 * різний матеріал на однакову роль читається як три випадкові дрібки, а не як
 * один інструмент.
 *
 * Регістр землі тут колись стояв словом («виміряно / здогад…») і смугою. Людині
 * він не каже нічого, крім одного: мапа тут давня. Лише це й лишилось.
 *
 * Кожен рядок чесно відсутній: немає камери — немає лінійки; мапа не давня —
 * немає застереження; полудень — немає світла; немає позиції —
 * немає ні світла, ні схилення. Немає жодного рядка — немає платівки.
 * Порожній прилад гірший за жоден.
 */
@Composable
internal fun MapInstrumentPlate(
    /** Саме dp, не пікселі екрана: смуга малюється в dp і підписується звідси. */
    metersPerDp: Double?,
    composition: RegisterComposition?,
    operatorLocation: local.phantom.companion.core.ai.context.LocationFix?,
    nowMs: Long,
    clockSkew: local.phantom.companion.core.geo.sky.ClockSkew?,
    /**
     * Чи повернуто мапу — тобто чи компас узагалі в ділі.
     *
     * Схилення міняється раз на рік, а стояло на екрані завжди, і саме воно
     * було найширшим рядком платівки: постійна біла плита в кутку заради
     * числа, яке потрібне рівно тоді, коли береш азимут. Тепер воно приходить
     * разом із поворотом мапи — і зникає, коли північ угорі.
     */
    mapRotated: Boolean = false,
    /**
     * Стиснутий стан: лишаються тільки масштаб і авторство.
     *
     * Існує тому, що платівку показували ЛИШЕ за відкритої панелі
     * інструментів — а разом із нею ховалось і авторство OSM, яке нижче в
     * цьому ж файлі названо безумовним. Регістр, світло і схилення — довідка,
     * і ховати їх у спокої правильно; ліцензія довідкою не є.
     */
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    val bar = remember(metersPerDp) { metersPerDp?.let(::scaleBarFor) }
    val register = composition?.takeIf { it.total > 0 && !compact }
    val light = rememberLightLine(operatorLocation, nowMs, clockSkew).takeIf { !compact }
    val declination = rememberDeclinationLine(operatorLocation, nowMs, clockSkew)
        .takeIf { mapRotated && !compact }
    // Раніше тут стояв ранній вихід, коли всі чотири прилади мовчать. Тепер
    // платівка несе ще й авторство OSM, а воно не має стану «немає»: ліцензія
    // не залежить від того, чи знає застосунок схилення. Тож виходу немає — є
    // мінімальний стан платівки, і він дорівнює одному рядку © .

    val scaleText = bar?.let { fmtDistance(LocalContext.current.resources, it.meters) }
    val staleWord = register?.dominant()?.first?.staleNoteRes()
        ?.let { stringResource(R.string.map_ground_stale) }
    val spoken = listOfNotNull(scaleText, staleWord, light?.spoken, declination?.spoken)
        .joinToString(" · ")

    Column(
        modifier = modifier
            // Стеля перша, інтринсик другий: інакше найдовший застережний
            // підпис («годинник не звірено з GPS») диктує ширину приладу.
            .widthIn(max = PLATE_MAX)
            .width(IntrinsicSize.Max)
            // Та сама піднята поверхня, що й пігулки та чипи над нею: радіус
            // контрола (10), бо це прилад, а не пігулка, і волосина.
            .clip(PlateShape)
            .background(c.raised)
            .border(1.dp, c.line, PlateShape)
            // Поле по вертикалі вдвічі тісніше за горизонтальне: рядки тут
            // короткі й вирівняні праворуч, тож повітря згори й знизу лише
            // роздувало плиту, нічого не додаючи до читаності.
            .padding(horizontal = PhantomSpace.s3, vertical = PhantomSpace.s1 + PhantomSpace.s0_5)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        if (bar != null && scaleText != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Лінійка — text2: це мірило, а не слово; число поруч — text.
                Row(
                    modifier = Modifier.width(bar.widthDp.dp).height(SCALE_TICK),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    ScaleTick(c.text2)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(SCALE_RULE)
                            .background(c.text2),
                    )
                    ScaleTick(c.text2)
                }
                Text(
                    text = scaleText,
                    style = MapFigureStyle,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                    maxLines = 1,
                )
            }
        }

        staleWord?.let {
            Text(text = it, style = PhantomText.caption, color = c.text2, maxLines = 2)
        }

        // Мінливіше вище, статичніше нижче: масштаб і застереження міняються від
        // кожного жесту, світло — раз на хвилину, схилення — раз на рік.
        //
        // Рядки світла складаються ТУТ, а не в MapLightReadout: виноска про
        // відкритий горизонт — константне речення, яке не міняється ніколи, і
        // окремим рядком воно лише вчило око пропускати цей кут екрана. Воно
        // нікуди не поділось — повністю живе в `spoken` вище (map_light_desc),
        // тобто там, де довжина нічого не коштує.
        // Застереження (годинник не звірено) — warning, не бурштин дії:
        // бурштином мапа кличе натиснути, а тут натискати нічого.
        light?.let { line ->
            Text(
                text = line.headline,
                style = MapFigureStyle,
                color = if (line.doubted) c.warning else c.text,
                maxLines = 1,
            )
            line.note?.let { note ->
                Text(
                    text = note,
                    style = PhantomText.caption,
                    color = if (line.doubted) c.warning else c.text2,
                    maxLines = 2,
                )
            }
        }
        declination?.let { MapDeclinationReadout(it) }

        // 13.09, рішення власника: авторство OSM більше НЕ на самій мапі —
        // воно лишається в Налаштування → Про застосунок/Ліцензії
        // (lic_map_body, settings_licenses_value), де вже стоїть повний текст
        // «© OpenStreetMap contributors, ODbL 1.0». ODbL вимагає доступності
        // «reasonably calculated to make aware», а не присутності на кожному
        // кадрі мапи — окремий, завжди досяжний екран ліцензій цю умову
        // задовольняє. MapAttributionAlwaysVisibleTest перенесено на цей
        // новий контракт.
    }
}

/** Стеля ширини приладу. Далі рядок про незвірений годинник тягнув би платівку
 *  на пів екрана — а ширша довідка не стає інформативнішою, лише гучнішою. */
private val PLATE_MAX: Dp = 176.dp

private val PlateShape = RoundedCornerShape(PhantomRadius.control)

/** Висота засічки й товщина лінійки: мірило читається вагою, не кроком сітки. */
private val SCALE_TICK: Dp = 6.dp
private val SCALE_RULE: Dp = 1.5.dp

/** Найбільша частка, а не найгірша: одна давня вулиця не робить давньою всю мапу. */
private fun RegisterComposition.dominant(): Pair<GroundRegister, Float>? = listOf(
    GroundRegister.MEASURED to measured,
    GroundRegister.DERIVED to derived,
    GroundRegister.GUESSED to guessed,
    GroundRegister.REMEMBERED to remembered,
).filter { it.second > 0f }.maxByOrNull { it.second }

@Composable
private fun ScaleTick(color: Color) {
    Box(Modifier.width(SCALE_RULE).height(SCALE_TICK).background(color))
}

/** The same four hexes used to be typed out again here, which made "one
 *  palette" true only by coincidence — and only on a dark ground.
 *  GroundRegister.coreColor is the one place a register becomes a colour. */
internal fun GroundRegister.tint(isDark: Boolean): Color = Color(coreColor(isDark))

/** The dark-ground reading, for callers with no theme in hand. */
internal fun GroundRegister.tint(): Color = tint(isDark = true)

internal data class ScaleBar(val meters: Double, val widthDp: Int)

/** The widest 1/2/5 × 10ⁿ distance that still fits [maxDp] of screen. */
internal fun scaleBarFor(metersPerDp: Double, maxDp: Int = 88): ScaleBar? {
    if (!metersPerDp.isFinite() || metersPerDp <= 0.0) return null
    val budget = metersPerDp * maxDp
    var best: Double? = null
    var step = 1.0
    while (step <= 10_000_000.0) {
        for (mult in intArrayOf(1, 2, 5)) {
            val candidate = step * mult
            if (candidate <= budget) best = candidate
        }
        step *= 10.0
    }
    val meters = best ?: return null
    val width = (meters / metersPerDp).toInt()
    return if (width < 24) null else ScaleBar(meters, width)
}

/** Web-Mercator ground resolution: 156543.03392 is z0's metres per 256px pixel. */
internal fun metersPerPixel(latitude: Double, zoom: Double): Double =
    156_543.033_92 * kotlin.math.cos(Math.toRadians(latitude)) / Math.pow(2.0, zoom)

/**
 * Тайл MapLibre — 512 px, а [metersPerPixel] рахує на 256: один зум розбіжності.
 */
private const val MAPLIBRE_TILE_RATIO = 2.0

/**
 * Метри на dp для камери MapLibre — єдине джерело підпису смуги масштабу.
 *
 * **Заміряно на A52 20.09** (1080×2400, щільність 420 = ×2,625). Три шпильки
 * довгим дотиком дали 216,0 px/км по обох осях, а смуга при цьому мала
 * суцільний відрізок 205 px — тобто 0,95 км — і підпис **5,0 км**. Після
 * подвійного дотику: 431,6 px/км, відрізок 163 px = 0,378 км, підпис
 * **2,0 км**. Похибка 5,27× і 5,30× — стала, отже не округлення.
 *
 * Стара формула була `metersPerPixel(lat, zoom) * density`, і вона помилялась
 * ДВІЧІ в один бік:
 *  1. `metersPerPixel` — це метри на піксель тайла **256 px**, а камера дає зум
 *     у конвенції MapLibre з тайлом **512 px**: бракувало ділення на 2;
 *  2. множення на щільність не мало сенсу взагалі — величина вже логічна
 *     (dp), а `density` перевела її в пікселі екрана і лишила підписаною як dp.
 *
 * Разом 2 × 2,625 = **5,25**, і саме це видно на склі. Арифметика сходиться до
 * знака: при zoom 12 стара формула дає 63,8 м/dp → підпис 5000 м на 78 dp =
 * 205 px (справжня довжина 948 м), при zoom 13 — 31,9 м/dp → 2000 м на 62 dp =
 * 163 px (справжня 377 м). Обидва числа — з апарата.
 *
 * Той самий урок уже записано за 70 рядків у `MapCameraLoop` про коло сумніву:
 * там джерелом узяли `map.projection`, тут зуму стачає, бо решта — чиста
 * геометрія.
 */
internal fun metersPerDp(latitude: Double, zoom: Double): Double =
    metersPerPixel(latitude, zoom) / MAPLIBRE_TILE_RATIO
