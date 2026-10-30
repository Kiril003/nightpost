package local.phantom.companion.feature.stream.ui.map.transit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors

/**
 * Колір лінії транспорту: правдивий — з даних, і лише коли даних нема —
 * запасний тон її виду.
 *
 * До 03.10 у пʼяти файлах аркушів мапи жило пʼять різних таблиць «вид → колір»
 * (картка зупинки, табло, хаб, вкладки хаба, пігулка флоту), і вони
 * сперечались: трамвай був то жовтим `#EAB308`, то гірчичним `#C98A12`, а
 * вкладка метро взагалі вигадувала «М1 — червона, М2 — синя» з номера лінії,
 * хоча `MetroLine.colour` лежав поруч. Тепер таблиця одна, і це єдиний файл
 * аркушів мапи, якому дозволено писати колір числом.
 *
 * Тони — глибокі, щоб світле слово на бейджі читалося і на нічному, і на
 * денному аркуші. Колір лінії малюється лише там, де він несе лінію: бейдж,
 * обідок маркера, знак виду. Решта аркуша говорить токенами теми.
 */
internal object TransitModeTint {

    private val METRO = Color(0xFFD64545)
    private val TRAM = Color(0xFFC98A12)
    private val TROLLEYBUS = Color(0xFF2F6FD6)
    private val BUS = Color(0xFF178A62)
    private val RAIL = Color(0xFF6A4FC4)
    private val FERRY = Color(0xFF0E8FA8)
    private val FUNICULAR = Color(0xFFC2611E)
    private val OTHER = Color(0xFF5B6770)

    /** Запасний тон за словом OSM / GTFS / Transitous («subway», «tram», «bus» …). */
    fun of(mode: String?): Color = when (mode?.lowercase()) {
        "subway", "metro" -> METRO
        "tram", "streetcar" -> TRAM
        "trolleybus" -> TROLLEYBUS
        "bus", "share_taxi", "minibus", "coach" -> BUS
        "train", "light_rail", "rail", "regional", "suburban", "monorail" -> RAIL
        "ferry" -> FERRY
        "funicular" -> FUNICULAR
        else -> OTHER
    }

    /** Запасний тон за видом розкладу RAPTOR. */
    fun of(mode: TransitMode): Color = when (mode) {
        TransitMode.METRO -> METRO
        TransitMode.TRAM -> TRAM
        TransitMode.TROLLEYBUS -> TROLLEYBUS
        TransitMode.BUS -> BUS
        TransitMode.RAIL -> RAIL
        TransitMode.FERRY -> FERRY
        TransitMode.FUNICULAR -> FUNICULAR
        TransitMode.OTHER -> OTHER
    }

    /** Колір лінії: ARGB із джерела, коли він є, інакше тон виду. */
    fun line(colourArgb: Int?, mode: String?): Color =
        colourArgb?.let { Color(it).copy(alpha = 1f) } ?: of(mode)

    /** Колір лінії з рядка «#RRGGBB» (метро-пак, шар маршруту); биті дані — тон виду. */
    fun line(hex: String?, mode: String?): Color = fromHex(hex) ?: of(mode)

    /** «#RRGGBB» або «#AARRGGBB» → колір; усе інше — null, не вигаданий сірий. */
    fun fromHex(hex: String?): Color? {
        val digits = hex?.trim()?.removePrefix("#") ?: return null
        val value = digits.toLongOrNull(16) ?: return null
        return when (digits.length) {
            6 -> Color((value or 0xFF000000L).toInt())
            8 -> Color(value.toInt())
            else -> null
        }
    }

    /**
     * Чорнило на бейджі кольору лінії: світле слово теми на темному тоні,
     * темне — на світлому (жовта лінія метро, світла гілка). Обидва — токени.
     */
    fun inkOn(fill: Color): Color = if (fill.luminance() > INK_FLIP) DayColors.text else NightColors.text

    private const val INK_FLIP = 0.42f
}
