package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * F-08 — the handover card: a position a stranger can act on after hearing it
 * once, over static, with no app on the far end.
 *
 * Three registers, deliberately different in strength:
 *  - [Mgrs] and [PlusCode] are EXACT math over the fix — zero map data.
 *  - the settlement sentence ([nameSettlementLine]) is measured but coarse,
 *    and needs `place=*` nodes; without them it says so instead of guessing.
 *
 * The one claim the math itself cannot make: the fix has a doubt radius, and
 * a 1-metre grid reference computed from a ±120 m fix is a lie told in exact
 * digits. So the printed precision is capped by the fix (see [precisionFor]).
 */

/** How many digits the fix can honestly carry, and what that means on the ground. */
internal data class HandoverPrecision(
    val mgrsDigits: Int,
    val mgrsMeters: Int,
    val plusCodeLength: Int,
    val plusCodeMeters: Int,
    /** Why this precision and not finer — shown, never inferred by the reader. */
    val reason: String,
)

/**
 * Digits are chosen by the fix's own accuracy, never by what the projection
 * could produce. An unknown accuracy is treated as the coarse case: a fix that
 * cannot state its doubt has not earned metre digits.
 */
internal fun precisionFor(accuracyM: Float?): HandoverPrecision = when {
    accuracyM == null -> HandoverPrecision(
        3, 100, 8, 275,
        "точність місця невідома — огрублено до 100 м / accuracy unknown — coarsened to 100 m",
    )
    accuracyM <= 5f -> HandoverPrecision(
        5, 1, 11, 3,
        "місце точне до ${accuracyM.roundToInt()} м / accurate to ${accuracyM.roundToInt()} m",
    )
    accuracyM <= 50f -> HandoverPrecision(
        4, 10, 10, 14,
        "місце точне до ${accuracyM.roundToInt()} м / accurate to ${accuracyM.roundToInt()} m",
    )
    accuracyM <= 500f -> HandoverPrecision(
        3, 100, 8, 275,
        "місце точне до ${accuracyM.roundToInt()} м / accurate to ${accuracyM.roundToInt()} m",
    )
    else -> HandoverPrecision(
        2, 1_000, 6, 5_500,
        "місце грубе — до ${accuracyM.roundToInt()} м / rough — up to ${accuracyM.roundToInt()} m",
    )
}

/**
 * Military Grid Reference System, WGS84, computed offline.
 *
 * UTM forward projection is the Snyder series (sub-millimetre inside a zone),
 * then the standard 100 km square lettering. Verified against the two published
 * fixed points the scheme is normally checked with: 0°,0° → `31N AA 66021
 * 00000`, and UTM easting 166021.44 / northing 0 at the same point.
 */
internal object Mgrs {
    private const val A = 6_378_137.0
    private const val F = 1.0 / 298.257223563
    private const val K0 = 0.9996
    private const val E2 = F * (2.0 - F)
    private const val EP2 = E2 / (1.0 - E2)

    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private val COLUMN_SETS = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private const val ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV"

    /** null outside the UTM band (|lat| > 84 / < −80) — MGRS is not defined there. */
    fun of(lat: Double, lon: Double, digits: Int): String? {
        if (lat < -80.0 || lat > 84.0) return null
        val d = digits.coerceIn(1, 5)
        val zone = zoneOf(lat, lon)
        val band = bandOf(lat) ?: return null
        val (easting, northing) = utm(lat, lon, zone)
        val column = floor(easting / 100_000.0).toInt()
        if (column !in 1..8) return null
        val columnLetter = COLUMN_SETS[(zone - 1) % 3][column - 1]
        var rowIndex = (floor(northing / 100_000.0).toLong() % 20L).toInt()
        // Even zones carry the row alphabet offset by five letters — the AA
        // scheme. Dropping this shifts every even-zone reference by 500 km.
        if (zone % 2 == 0) rowIndex = (rowIndex + 5) % 20
        val rowLetter = ROW_LETTERS[rowIndex]
        val divisor = intPow10(5 - d)
        val e = (floor(easting).toLong() % 100_000L) / divisor
        val n = (floor(northing).toLong() % 100_000L) / divisor
        return "$zone$band $columnLetter$rowLetter " +
            e.toString().padStart(d, '0') + " " + n.toString().padStart(d, '0')
    }

    private fun intPow10(p: Int): Long {
        var out = 1L
        repeat(p) { out *= 10L }
        return out
    }

    internal fun zoneOf(lat: Double, lon: Double): Int {
        var zone = floor((lon + 180.0) / 6.0).toInt() + 1
        if (lat in 56.0..63.999999 && lon >= 3.0 && lon < 12.0) zone = 32
        if (lat >= 72.0 && lat < 84.0) {
            zone = when {
                lon >= 0.0 && lon < 9.0 -> 31
                lon >= 9.0 && lon < 21.0 -> 33
                lon >= 21.0 && lon < 33.0 -> 35
                lon >= 33.0 && lon < 42.0 -> 37
                else -> zone
            }
        }
        return zone.coerceIn(1, 60)
    }

    internal fun bandOf(lat: Double): Char? {
        if (lat < -80.0 || lat > 84.0) return null
        if (lat >= 72.0) return 'X'
        val i = floor((lat + 80.0) / 8.0).toInt().coerceIn(0, 19)
        return BANDS[i]
    }

    /** (easting, northing) metres in [zone], southern hemisphere already offset. */
    internal fun utm(lat: Double, lon: Double, zone: Int): Pair<Double, Double> {
        val phi = Math.toRadians(lat)
        val lambda = Math.toRadians(lon)
        val lambda0 = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)
        val n = A / sqrt(1.0 - E2 * sin(phi) * sin(phi))
        val t = tan(phi) * tan(phi)
        val c = EP2 * cos(phi) * cos(phi)
        val a = cos(phi) * (lambda - lambda0)
        val m = A * (
            (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256) * phi -
                (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024) * sin(2 * phi) +
                (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024) * sin(4 * phi) -
                (35 * E2 * E2 * E2 / 3072) * sin(6 * phi)
            )
        val easting = K0 * n * (
            a + (1 - t + c) * a * a * a / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a * a * a * a * a / 120
            ) + 500_000.0
        var northing = K0 * (
            m + n * tan(phi) * (
                a * a / 2 +
                    (5 - t + 9 * c + 4 * c * c) * a * a * a * a / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a * a * a * a * a * a / 720
                )
            )
        if (lat < 0) northing += 10_000_000.0
        return easting to northing
    }
}

/**
 * Open Location Code (Plus Code), the public-domain scheme.
 *
 * The alphabet drops `0/O`, `1/I/L` and every vowel on purpose: this is the
 * one code in the card that gets written on a hand. Verified against the
 * reference vectors published with the algorithm (e.g. 47.0000625, 8.0000625
 * → `8FVC2222+22`; 20.3701135, 2.78223535 at length 13 → `7FG49QCJ+2VXGJ`).
 */
internal object PlusCode {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val SEPARATOR_POSITION = 8
    private const val LAT_UNITS = 25_000_000.0 // 1/8000 of a degree, five grid levels down
    private const val LON_UNITS = 8_192_000.0

    fun encode(lat: Double, lon: Double, length: Int): String {
        val len = length.coerceIn(2, 15)
        var latitude = lat.coerceIn(-90.0, 90.0)
        if (latitude >= 90.0) latitude = 90.0 - 1e-9
        var longitude = lon
        longitude = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        var latValue = (floor(latitude * LAT_UNITS) + 90.0 * 25_000_000.0).toLong()
        var lonValue = (floor(longitude * LON_UNITS) + 180.0 * 8_192_000.0).toLong()
        val chars = CharArray(15) { '2' }
        if (len > 10) {
            for (i in 4 downTo 0) {
                val d = ((latValue % 5L) * 4L + (lonValue % 4L)).toInt()
                chars[10 + i] = ALPHABET[d]
                latValue /= 5L
                lonValue /= 4L
            }
        } else {
            latValue /= 3125L // 5^5
            lonValue /= 1024L // 4^5
        }
        for (i in 4 downTo 0) {
            chars[i * 2] = ALPHABET[(latValue % 20L).toInt()]
            chars[i * 2 + 1] = ALPHABET[(lonValue % 20L).toInt()]
            latValue /= 20L
            lonValue /= 20L
        }
        val body = String(chars)
        return if (len < SEPARATOR_POSITION) {
            body.substring(0, len) + "0".repeat(SEPARATOR_POSITION - len) + "+"
        } else {
            body.substring(0, SEPARATOR_POSITION) + "+" + body.substring(SEPARATOR_POSITION, len)
        }
    }
}

internal const val EARTH_RADIUS_M = 6_371_000.0

internal fun greatCircleMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** Initial great-circle bearing from 1 → 2, degrees clockwise from true north. */
internal fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

private val COMPASS_16_UK = listOf(
    "Пн", "ПнПнСх", "ПнСх", "СхПнСх",
    "Сх", "СхПдСх", "ПдСх", "ПдПдСх",
    "Пд", "ПдПдЗх", "ПдЗх", "ЗхПдЗх",
    "Зх", "ЗхПнЗх", "ПнЗх", "ПнПнЗх",
)

private val COMPASS_16_EN = listOf(
    "N", "NNE", "NE", "ENE",
    "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW",
    "W", "WNW", "NW", "NNW",
)

private val COMPASS_16_SPOKEN_UK = listOf(
    "північ", "північ-північний схід", "північний схід", "схід-північний схід",
    "схід", "схід-південний схід", "південний схід", "південь-південний схід",
    "південь", "південь-південний захід", "південний захід", "захід-південний захід",
    "захід", "захід-північний захід", "північний захід", "північ-північний захід",
)

internal fun compassIndex16(bearingDeg: Double): Int =
    (((bearingDeg % 360.0 + 360.0) % 360.0) / 22.5).roundToInt() % 16

internal fun compassAbbrevUk(bearingDeg: Double): String = COMPASS_16_UK[compassIndex16(bearingDeg)]

internal fun compassAbbrevEn(bearingDeg: Double): String = COMPASS_16_EN[compassIndex16(bearingDeg)]

internal fun compassSpokenUk(bearingDeg: Double): String = COMPASS_16_SPOKEN_UK[compassIndex16(bearingDeg)]

/**
 * Distance rounded to a step a voice channel survives: nearest 100 m under
 * 2 km, nearest 500 m beyond. A "2 347 м" read over static is four chances to
 * mishear; "2,5 км" is one.
 */
internal fun voiceDistance(meters: Double): String {
    if (meters < 100.0) return "${(meters / 10.0).roundToInt() * 10} м"
    if (meters < 2_000.0) return "${(meters / 100.0).roundToInt() * 100} м"
    val km = (meters / 500.0).roundToLong() * 500L / 1000.0
    return if (abs(km - km.roundToLong()) < 0.01) {
        "${km.roundToLong()} км"
    } else {
        String.format(Locale.US, "%.1f км", km).replace('.', ',')
    }
}

/** A `place=*` node considered as the name a stranger could plausibly know. */
internal data class SettlementCandidate(
    val name: String,
    val lat: Double,
    val lon: Double,
    /** OSM `place` value: city / town / village / hamlet / locality / … */
    val placeKind: String?,
    val tags: Map<String, String> = emptyMap(),
)

/**
 * How much further a better-known kind is allowed to be. The nearest node is
 * often an unnamed `locality` nobody could route on; the spec's rule is that
 * a `village` a little further off wins.
 */
private fun recognisabilityWeight(placeKind: String?): Double = when (placeKind?.lowercase()) {
    "city" -> 0.5
    "town" -> 0.6
    "village" -> 1.0
    "suburb", "borough", "quarter", "neighbourhood" -> 1.4
    "hamlet" -> 1.6
    "isolated_dwelling", "farm", "locality" -> 3.0
    else -> 2.0
}

internal fun pickRecognisableSettlement(
    lat: Double,
    lon: Double,
    candidates: List<SettlementCandidate>,
): SettlementCandidate? = candidates
    .filter { it.name.isNotBlank() }
    .minByOrNull { greatCircleMeters(lat, lon, it.lat, it.lon) * recognisabilityWeight(it.placeKind) }

private val DISTRICT_KEYS = listOf("addr:district", "is_in:district", "addr:subdistrict", "district")
private val REGION_KEYS = listOf("addr:region", "is_in:region", "addr:state", "is_in:state")

/**
 * «2,5 км на південний схід від Іванівки, Х-ський район».
 *
 * Raion is not decoration: identically-named villages sit in different oblasts
 * all over Ukraine. When no tag carries it, the line says the name is
 * ambiguous rather than shipping a bare village name that could send a crew to
 * the wrong oblast.
 */
internal fun nameSettlementLine(
    lat: Double,
    lon: Double,
    settlement: SettlementCandidate?,
): String {
    if (settlement == null) {
        return "Населений пункт не визначено — немає даних місцевості. " +
            "Передавай MGRS або плюс-код. / No settlement data — hand over MGRS or the Plus Code."
    }
    val meters = greatCircleMeters(settlement.lat, settlement.lon, lat, lon)
    val bearing = bearingDegrees(settlement.lat, settlement.lon, lat, lon)
    val district = DISTRICT_KEYS.firstNotNullOfOrNull { settlement.tags[it]?.takeIf { v -> v.isNotBlank() } }
    val region = REGION_KEYS.firstNotNullOfOrNull { settlement.tags[it]?.takeIf { v -> v.isNotBlank() } }
    val place = buildString {
        append(settlement.name)
        if (district != null) append(", $district")
        if (region != null) append(", $region")
    }
    val head = "${voiceDistance(meters)} на ${compassSpokenUk(bearing)} від $place " +
        "(${compassAbbrevUk(bearing)} / ${compassAbbrevEn(bearing)}, ${bearing.roundToInt()}°)"
    return if (district == null && region == null) {
        "$head. Район не позначено в даних — назва може повторюватись в іншій області, " +
            "уточни. / District not tagged — the name may repeat in another oblast."
    } else {
        head
    }
}

/**
 * Everything a stranger needs, in three registers that fail independently.
 * [settlementLine] degrades on its own without touching the two exact codes.
 */
internal data class HandoverCard(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val precision: HandoverPrecision,
    val mgrs: String?,
    val plusCode: String,
    val settlementLine: String,
    /** The node the landmark sentence was built from; null = no place data. */
    val settlement: SettlementCandidate? = null,
) {
    /** Read-back order: exact first, then the one that needs nothing on the far end. */
    fun lines(): List<String> = listOf(
        "MGRS: ${mgrs ?: "поза зоною UTM — недоступно / outside the UTM band"} " +
            "(${precision.mgrsDigits} цифр · ${precision.mgrsMeters} м)",
        "Плюс-код / Plus Code: $plusCode (≈${precision.plusCodeMeters} м)",
        "Орієнтир / Landmark: $settlementLine",
        "Точність / Precision: ${precision.reason}",
        "Прочитай назад / Read back: попроси повторити MGRS по знаку.",
    )

    fun toJson(): JsonObject = buildJsonObject {
        put("lat", lat)
        put("lon", lon)
        accuracyM?.let { put("fix_accuracy_m", it.toDouble()) }
        put("mgrs", mgrs ?: "")
        put("mgrs_available", mgrs != null)
        put("mgrs_digits", precision.mgrsDigits)
        put("mgrs_precision_m", precision.mgrsMeters)
        put("plus_code", plusCode)
        put("plus_code_precision_m", precision.plusCodeMeters)
        put("settlement_line", settlementLine)
        // The sentence is for the radio; these are for anything that has to
        // sort, compare or re-draw the landmark instead of read it aloud.
        put("settlement_known", settlement != null)
        settlement?.let { s ->
            put("settlement_name", s.name)
            put("settlement_lat", s.lat)
            put("settlement_lon", s.lon)
            s.placeKind?.let { put("settlement_kind", it) }
            val meters = greatCircleMeters(s.lat, s.lon, lat, lon)
            put("settlement_straight_line_m", meters)
            put("settlement_bearing_deg", bearingDegrees(s.lat, s.lon, lat, lon).roundToInt())
            put("settlement_bearing", compassAbbrevUk(bearingDegrees(s.lat, s.lon, lat, lon)))
            put("settlement_district_known", DISTRICT_KEYS.any { k -> !s.tags[k].isNullOrBlank() })
        }
        put("precision_reason", precision.reason)
        put("register", "computed_exact")
        put("source", "wgs84_math_offline")
        put("lines", buildJsonArray { lines().forEach { add(it) } })
    }
}

internal fun buildHandoverCard(
    lat: Double,
    lon: Double,
    accuracyM: Float?,
    settlements: List<SettlementCandidate>,
): HandoverCard {
    val precision = precisionFor(accuracyM)
    val settlement = pickRecognisableSettlement(lat, lon, settlements)
    return HandoverCard(
        lat = lat,
        lon = lon,
        accuracyM = accuracyM,
        precision = precision,
        mgrs = Mgrs.of(lat, lon, precision.mgrsDigits),
        plusCode = PlusCode.encode(lat, lon, precision.plusCodeLength),
        settlementLine = nameSettlementLine(lat, lon, settlement),
        settlement = settlement,
    )
}
