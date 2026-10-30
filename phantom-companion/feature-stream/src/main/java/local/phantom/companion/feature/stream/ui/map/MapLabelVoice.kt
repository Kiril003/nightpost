package local.phantom.companion.feature.stream.ui.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Голос мапи.
 *
 * Досі його не було. [applyGroundCartography] забрала в постачальника колір
 * землі й мову підпису — але КЕГЕЛЬ, вагу, регістр і черговість лишила
 * чужими: місто, село, вулиця і номер дороги приходили з драбини, складеної
 * для світового стилю, де українських назв ніхто не міряв. На екрані це рівний
 * сірий шум, у якому щит «Р-04» важить стільки ж, скільки «ЖИТОМИР», і око
 * знаходить не місце, а дорогу.
 *
 * Драбина не вигадана: кожен щабель приколотий до кегля ролі, якою застосунок
 * уже говорить (PhantomTextRoles) — 16 прози, 13 тіла, 11 підпису, 10 кікера.
 * Мапа не заводить шостої типографіки, вона говорить голосами продукту.
 *
 * ЧЕРГОВІСТЬ: тільки ПІСЛЯ [applyGroundCartography]. Та кладе поле підпису,
 * стек шрифту й чорнило; ця — кегель, регістр, ореол і право поступитися.
 * Навпаки: переписане поле знесе кегель разом із собою.
 */
internal fun applyLabelVoice(style: Style, isDark: Boolean) {
    // Обсидіан народжується з власним голосом — див. [Style.speaksObsidian].
    if (style.speaksObsidian()) return
    val bold = boldestStack(style)
    for (layer in style.layers) {
        if (layer !is SymbolLayer) continue
        // Один зіпсований шар не має права лишити всю решту мапи чужим
        // голосом: чужий стиль може принести підпис, що не тримає властивість,
        // яку ми йому даємо. Той самий захист, що в [applyGroundCartography].
        runCatching {
            when (layer.sourceLayer) {
                "place" -> layer.setProperties(*placeVoice(bold, isDark))
                "mountain_peak" -> layer.setProperties(*plainVoice(LabelTier.PLACE_SMALL, isDark))
                "water_name" -> layer.setProperties(*plainVoice(LabelTier.WATER, isDark))
                "transportation_name" -> layer.setProperties(
                    *plainVoice(
                        if (labelsAName(textFieldOf(layer))) LabelTier.STREET else LabelTier.ROAD_REF,
                        isDark,
                    ),
                )
                "poi" -> layer.setProperties(*plainVoice(LabelTier.POI, isDark))
                "housenumber" -> layer.setProperties(*plainVoice(LabelTier.HOUSENUMBER, isDark))
            }
        }
    }
}

/**
 * Щабель драбини. Порядок оголошення — це і є порядок важливості: [ordinal]
 * стає ключем сортування, а менший ключ виграє місце в рушії. Пересунути
 * щабель посередині означає свідомо змінити, хто кому поступається.
 */
private enum class LabelTier {
    PLACE_MAJOR,
    PLACE_TOWN,
    PLACE_SMALL,
    WATER,
    STREET,
    ROAD_REF,
    POI,
    HOUSENUMBER,
}

/* ── Драбина кеглів: щабель мапи ↔ кегель ролі застосунку ─────────────────── */

private const val ROLE_PROSE = 16f
private const val ROLE_BODY = 13f
private const val ROLE_CAPTION = 11f
private const val ROLE_KICKER = 10f

/**
 * Населений пункт — єдиний щабель, який рушій не ділить за нас: обласний
 * центр і хутір лежать в одному `place`, тож клас читається виразом, а не
 * іменем шару. Саме тут це найдорожче: без розбору кожен хутір став би
 * шістнадцятикегельним прописним заголовком.
 */
private fun placeVoice(bold: Array<String>, isDark: Boolean): Array<PropertyValue<*>> = arrayOf(
    PropertyFactory.textSize(
        classRamp(
            city = listOf(4 to ROLE_CAPTION, 7 to ROLE_BODY, 11 to ROLE_PROSE, 14 to ROLE_PROSE + 2f),
            town = listOf(7 to ROLE_KICKER, 10 to ROLE_CAPTION + 1f, 14 to ROLE_BODY + 1f),
            small = listOf(9 to ROLE_KICKER - 0.5f, 12 to ROLE_CAPTION, 15 to ROLE_BODY - 0.5f),
        ),
    ),
    // Вага — усьому шару, а не виразом за класом. `text-font` — єдина
    // властивість тут, де промах не видно: невідомий стек дає 404 на сервері
    // гліфів і підпис зникає мовчки. Простий масив бере той самий шлях, яким
    // уже ходить [applyGroundCartography]; вираз ішов би іншим.
    PropertyFactory.textFont(bold),
    // Прописні — тільки обласному центру. Той самий жест, яким застосунок
    // пише свої кікери, і він робить назву міста єдиним підписом на мапі,
    // який видно, не читаючи.
    PropertyFactory.textTransform(
        classMatch(
            city = Expression.literal(Property.TEXT_TRANSFORM_UPPERCASE),
            town = Expression.literal(Property.TEXT_TRANSFORM_NONE),
            small = Expression.literal(Property.TEXT_TRANSFORM_NONE),
        ),
    ),
    PropertyFactory.textLetterSpacing(
        classMatch(
            city = Expression.literal(0.14f),
            town = Expression.literal(0.02f),
            small = Expression.literal(0.0f),
        ),
    ),
    // Вісім знаків на рядок замість десяти. «КАМʼЯНЕЦЬ-ПОДІЛЬСЬКИЙ» одним
    // рядком — 264 dp на 411-точковому екрані власника, а при масштабі шрифту
    // 1,15 рушій такий підпис просто викидає. Два коротші рядки лишаються.
    PropertyFactory.textMaxWidth(8f),
    PropertyFactory.textLineHeight(1.1f),
    PropertyFactory.symbolSortKey(
        classMatch(
            city = Expression.literal(LabelTier.PLACE_MAJOR.ordinal.toFloat()),
            town = Expression.literal(LabelTier.PLACE_TOWN.ordinal.toFloat()),
            small = Expression.literal(LabelTier.PLACE_SMALL.ordinal.toFloat()),
        ),
    ),
    PropertyFactory.textHaloWidth(halo(LabelTier.PLACE_MAJOR, isDark)),
    PropertyFactory.textHaloBlur(HALO_BLUR),
)

private fun plainVoice(tier: LabelTier, isDark: Boolean): Array<PropertyValue<*>> {
    val base = mutableListOf<PropertyValue<*>>(
        PropertyFactory.textSize(sizeRamp(tier)),
        PropertyFactory.textHaloWidth(halo(tier, isDark)),
        PropertyFactory.textHaloBlur(HALO_BLUR),
        PropertyFactory.symbolSortKey(tier.ordinal.toFloat()),
    )
    when (tier) {
        LabelTier.WATER -> {
            base += PropertyFactory.textLetterSpacing(0.08f)
            base += PropertyFactory.textMaxWidth(9f)
        }
        LabelTier.PLACE_SMALL -> base += PropertyFactory.textMaxWidth(9f)
        LabelTier.STREET -> base += PropertyFactory.textLetterSpacing(0.02f)
        LabelTier.ROAD_REF -> {
            // Номер дороги — дані, і поводиться як дані: розрядка, менший
            // кегель, і зникає ПЕРШИМ, коли двом підписам тісно. Досі щит
            // вигравав у назви міста саме тим, що коротший.
            base += PropertyFactory.textLetterSpacing(0.06f)
            base += PropertyFactory.textAllowOverlap(false)
            base += PropertyFactory.textOptional(true)
            base += PropertyFactory.textPadding(4f)
        }
        LabelTier.POI, LabelTier.HOUSENUMBER -> {
            base += PropertyFactory.textAllowOverlap(false)
            base += PropertyFactory.textOptional(true)
        }
        LabelTier.PLACE_MAJOR, LabelTier.PLACE_TOWN -> Unit
    }
    return base.toTypedArray()
}

private fun sizeRamp(tier: LabelTier): Expression = when (tier) {
    LabelTier.PLACE_MAJOR -> ramp(4 to ROLE_CAPTION, 11 to ROLE_PROSE)
    LabelTier.PLACE_TOWN -> ramp(7 to ROLE_KICKER, 14 to ROLE_BODY + 1f)
    LabelTier.PLACE_SMALL -> ramp(9 to ROLE_KICKER - 0.5f, 12 to ROLE_CAPTION, 15 to ROLE_BODY - 0.5f)
    LabelTier.WATER -> ramp(6 to ROLE_KICKER, 10 to ROLE_CAPTION + 1f, 14 to ROLE_BODY + 0.5f)
    LabelTier.STREET -> ramp(13 to ROLE_KICKER, 16 to ROLE_CAPTION + 0.5f)
    LabelTier.ROAD_REF -> ramp(8 to ROLE_KICKER - 1f, 14 to ROLE_KICKER)
    LabelTier.POI -> ramp(14 to ROLE_KICKER, 16 to ROLE_CAPTION, 18 to ROLE_BODY)
    LabelTier.HOUSENUMBER -> ramp(15 to ROLE_KICKER, 17 to ROLE_CAPTION)
}

/**
 * Ореол — не тінь, а діра. Виміряно: підпис вулиці, що перетинає трасу,
 * втрачає літери в обвідці дороги (1,11:1 удень), і мʼяке коло цього не
 * пробиває. Тож край твердий, а ширина росте разом із кеглем, який має
 * пробити. Уночі чверть додатково: чорнило й ґрунт там ближчі одне до одного.
 */
private const val HALO_BLUR = 0.2f

private fun halo(tier: LabelTier, isDark: Boolean): Float {
    val base = when (tier) {
        LabelTier.PLACE_MAJOR -> 2.0f
        LabelTier.PLACE_TOWN -> 1.9f
        LabelTier.WATER -> 1.8f
        LabelTier.PLACE_SMALL, LabelTier.STREET -> 1.6f
        LabelTier.POI -> 1.4f
        LabelTier.ROAD_REF -> 1.2f
        LabelTier.HOUSENUMBER -> 1.0f
    }
    return if (isDark) base * 1.25f else base
}

/** match(get("class"), city → …, town → …, default → …) — хутір потрапляє у default. */
private fun classMatch(city: Expression, town: Expression, small: Expression): Expression =
    Expression.match(
        Expression.get("class"),
        Expression.literal("city"), city,
        Expression.literal("town"), town,
        small,
    )

private fun ramp(vararg stops: Pair<Int, Float>): Expression = Expression.interpolate(
    Expression.linear(),
    Expression.zoom(),
    *stops.map { (z, size) -> Expression.stop(z, size) }.toTypedArray(),
)

/**
 * Кегель, що залежить і від зуму, і від класу — але в тому порядку, який
 * рушій приймає.
 *
 * `match(get("class"), ramp(zoom), …)` компілюється, читається природно і НЕ
 * ДОЇЖДЖАЄ: `zoom()` дозволено лише прямим входом ЗОВНІШНЬОГО interpolate, а
 * загорнутий у match він відкидається разом із `text-size`. Тобто драбина
 * ролей, заради якої весь цей файл існує, мовчки не діяла на жодному підписі
 * населеного пункту.
 *
 * Тому interpolate тут один, а розгалуження за класом їде у ВИХОДИ зупинок.
 * Зупинки — обʼєднання зламів усіх трьох кривих: між сусідніми зламами кожна
 * крива лінійна, тож вибірка саме в зламах відтворює її точно, а не приблизно
 * ([MapLabelVoiceTest]).
 */
private fun classRamp(
    city: List<Pair<Int, Float>>,
    town: List<Pair<Int, Float>>,
    small: List<Pair<Int, Float>>,
): Expression {
    val knots = (city + town + small).map { it.first }.distinct().sorted()
    val stops = knots.map { z ->
        Expression.stop(
            z,
            classMatch(
                city = Expression.literal(rampAt(city, z)),
                town = Expression.literal(rampAt(town, z)),
                small = Expression.literal(rampAt(small, z)),
            ),
        )
    }
    return Expression.interpolate(Expression.linear(), Expression.zoom(), *stops.toTypedArray())
}

/** Кусково-лінійна крива в точці; за власними межами тримає крайній злам —
 *  рівно як робить `interpolate`. */
internal fun rampAt(stops: List<Pair<Int, Float>>, z: Int): Float {
    if (z <= stops.first().first) return stops.first().second
    if (z >= stops.last().first) return stops.last().second
    val hi = stops.indexOfFirst { it.first >= z }
    val (z0, v0) = stops[hi - 1]
    val (z1, v1) = stops[hi]
    return v0 + (v1 - v0) * (z - z0) / (z1 - z0).toFloat()
}

/**
 * Жирний стек беремо зі стилю, а не з голови.
 *
 * Сервер гліфів віддає 404 на невідомий стек, і підпис просто не малюється —
 * без збою й без рядка в лозі (це вже коштувало виїзду 17.07). Тож єдина
 * безпечна вага — та, яку стиль сам уже просить у того ж сервера. Немає такої
 * — лишаємось на одному перевіреному стеку, і ієрархію несуть кегель, регістр
 * і розрядка.
 */
private fun boldestStack(style: Style): Array<String> {
    val stacks = style.layers.filterIsInstance<SymbolLayer>().mapNotNull { it.textFont.value }
    return stacks.firstOrNull { s -> s.any { it.contains("Bold", ignoreCase = true) } }
        ?: stacks.firstOrNull { s -> s.any { it.contains("Medium", ignoreCase = true) } }
        ?: MAP_TEXT_FONT
}

private fun textFieldOf(layer: SymbolLayer): String? {
    val field = layer.textField
    return if (field.isExpression) field.expression?.toString() else field.value?.toString()
}
