package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialLine
import local.phantom.companion.core.data.spatial.SpatialMarker
import local.phantom.companion.core.net.map.OsrmClient
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.util.Locale
import kotlin.math.roundToInt

/**
 * F-02 + F-08 — the medevac backbone (docs/design/spec-medevac-shelter.md §1.3, §2).
 *
 * F-02: "the nearest hospital" is a ROUTED answer or it is not an answer. A
 * river, a dropped bridge or a one-way restriction routinely makes the
 * third-nearest facility by straight line the actual nearest by road, and the
 * straight-line number is told at the exact moment a crew can least afford it.
 * When nothing routes, this says «маршруту немає» — it never falls back to a
 * great-circle number wearing the word "distance".
 *
 * F-08: the handover card rides along unasked, because the crew that needs a
 * hospital also needs to hand its own position to someone with no app.
 */

/** One candidate facility, flattened out of whatever POI store found it. */
data class MedevacPoi(
    val name: String,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, String> = emptyMap(),
)

/** Radius lookup against the app's POI store; the tool never owns one. */
typealias PoiLookupFn =
    suspend (lat: Double, lon: Double, radiusM: Int, selector: String) -> List<MedevacPoi>

/**
 * 1→N routed durations, seconds. A null ENTRY means that destination has no
 * route; a null LIST means the router itself never answered. The two are not
 * the same claim and are not collapsed anywhere below.
 */
typealias RouteMatrixFn = suspend (
    profile: RouteProfile,
    fromLat: Double,
    fromLon: Double,
    destinations: List<Pair<Double, Double>>,
) -> List<Double?>?

/** One routed leg with geometry. null = no route — never a straight line. */
typealias RouteLegFn = suspend (
    profile: RouteProfile,
    fromLat: Double,
    fromLon: Double,
    toLat: Double,
    toLon: Double,
) -> RoutedLeg?

data class RoutedLeg(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val points: List<Pair<Double, Double>>,
)

/** Which candidate the ROAD says is nearest, and how much the router actually answered. */
internal data class RoutedPick(
    val index: Int?,
    val durationSeconds: Double?,
    val routedCount: Int,
    val askedCount: Int,
    val routerAnswered: Boolean,
)

/**
 * Straight-line order is used to pick which candidates to ASK about, never to
 * answer. Routed distance is always ≥ great-circle distance, so the nearest by
 * road is overwhelmingly inside the nearest few by line — but "overwhelmingly"
 * is not "always", which is why the cap is reported to the operator rather
 * than hidden.
 */
internal const val MEDEVAC_CANDIDATE_CAP = 6

/** Per-candidate leg calls when the matrix endpoint is absent — bounded, still routed. */
internal const val MEDEVAC_LEG_FALLBACK_CAP = 3

internal suspend fun pickRoutedNearest(
    profile: RouteProfile,
    fromLat: Double,
    fromLon: Double,
    candidates: List<MedevacPoi>,
    routeMatrix: RouteMatrixFn,
    routeLeg: RouteLegFn,
): RoutedPick {
    if (candidates.isEmpty()) return RoutedPick(null, null, 0, 0, false)
    val destinations = candidates.map { it.lat to it.lon }
    val durations = routeMatrix(profile, fromLat, fromLon, destinations)
    if (durations != null && durations.size == destinations.size) {
        val answered = durations.count { it != null }
        val best = durations.withIndex().filter { it.value != null }.minByOrNull { it.value!! }
        return RoutedPick(best?.index, best?.value, answered, destinations.size, true)
    }
    // No matrix endpoint: ask for real legs, fewer of them. Still routed.
    var bestIndex: Int? = null
    var bestSeconds: Double? = null
    var answered = 0
    val asked = minOf(MEDEVAC_LEG_FALLBACK_CAP, candidates.size)
    for (i in 0 until asked) {
        val leg = routeLeg(profile, fromLat, fromLon, candidates[i].lat, candidates[i].lon) ?: continue
        answered++
        if (bestSeconds == null || leg.durationSeconds < bestSeconds) {
            bestSeconds = leg.durationSeconds
            bestIndex = i
        }
    }
    return RoutedPick(bestIndex, bestSeconds, answered, asked, false)
}

private fun medevacManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 0,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    abusableAgainstWeak = false,
    sunsetAfterDaysIdle = 90,
    surfaces = setOf(FeatureManifest.Surface.STREAM),
)

/**
 * OSM `place=*` nodes a stranger could plausibly know by name.
 *
 * Deliberately unanchored: OfflinePoiPack.parseSelector splits `key~"a|b"` by
 * hand, so `^(a|b)$` would make every offline lookup match nothing — and
 * offline is the case this product exists for.
 */
internal const val SETTLEMENT_SELECTOR =
    "place~\"city|town|village|hamlet|suburb|neighbourhood|isolated_dwelling|locality\""

internal const val SETTLEMENT_RADIUS_M = 12_000

internal suspend fun handoverCardFor(
    lat: Double,
    lon: Double,
    accuracyM: Float?,
    findPois: PoiLookupFn,
): HandoverCard {
    val places = runCatching { findPois(lat, lon, SETTLEMENT_RADIUS_M, SETTLEMENT_SELECTOR) }
        .getOrDefault(emptyList())
        .map {
            SettlementCandidate(
                name = it.name,
                lat = it.lat,
                lon = it.lon,
                placeKind = it.tags["place"],
                tags = it.tags,
            )
        }
    return buildHandoverCard(lat, lon, accuracyM, places)
}

internal data class MedevacKind(val label: String, val selector: String, val synonyms: Set<String>)

internal val MEDEVAC_KINDS = listOf(
    MedevacKind("лікарня", "amenity~\"hospital|clinic\"", setOf("лікарня", "лікарні", "hospital", "лікування", "медицина", "госпіталь")),
    MedevacKind("клініка", "amenity~\"clinic|doctors\"", setOf("клініка", "клініки", "clinic", "амбулаторія", "лікар", "doctors")),
    MedevacKind("аптека", "amenity=pharmacy", setOf("аптека", "аптеки", "pharmacy", "ліки")),
)

internal fun resolveMedevacKind(raw: String?): MedevacKind {
    val q = raw?.trim()?.lowercase().orEmpty()
    return MEDEVAC_KINDS.firstOrNull { q == it.label || q in it.synonyms } ?: MEDEVAC_KINDS.first()
}

/**
 * F-01/§1.2 — the ER badge is positive-only. `emergency=yes` sits on 14.5% of
 * Ukrainian hospitals, so a missing tag is missing data. Rendering it as "no
 * ER" would manufacture a negative claim OSM never made about 85% of the
 * country's hospitals.
 */
internal fun erLine(tags: Map<String, String>): String = when {
    tags["emergency"] == "yes" ->
        "приймальне: позначено emergency=yes / ER: tagged"
    else ->
        "приймальне: НЕВІДОМО — на мапі не позначено, це не означає «немає» / ER: not marked on the map, not a denial"
}

private fun fmtKmMedevac(meters: Double): String =
    if (meters >= 1000) String.format(Locale.US, "%.1f км", meters / 1000.0)
    else "${meters.roundToInt()} м"

private fun fmtMinMedevac(seconds: Double): String {
    val min = (seconds / 60.0).roundToInt()
    return if (min < 60) "$min хв" else String.format(Locale.US, "%dг %02dхв", min / 60, min % 60)
}

/**
 * `medevac.nearest` — routed-nearest medical facility, plus the handover card.
 */
class MedevacNearestTool(
    private val findPois: PoiLookupFn,
    private val sink: SpatialLayerSink,
    private val locationProvider: LocationContextProvider?,
    private val currentThreadId: () -> String?,
    private val requestMapOpen: () -> Unit = {},
    private val osrm: Lazy<OsrmClient> = lazy { OsrmClient() },
    private val routeMatrix: RouteMatrixFn = { profile, lat, lon, dest ->
        osrm.value.table(profile, lat, lon, dest)?.map { it?.toDouble() }
    },
    private val routeLeg: RouteLegFn = { profile, fLat, fLon, tLat, tLon ->
        osrm.value.route(profile, fLat, fLon, tLat, tLon)
            ?.let { RoutedLeg(it.distanceMeters, it.durationSeconds, it.points) }
    },
) : Tool {

    override val id = "medevac.nearest"

    override val description =
        "Найближчий медичний заклад ЗА ДОРОГОЮ (не по прямій) + картка передачі позиції по радіо. " +
            "Уживай це на будь-яке «найближча лікарня / куди везти пораненого / де аптека», а не geo.nearby: " +
            "ранжує кандидатів за РЕАЛЬНИМ часом у дорозі, а коли маршруту немає — так і каже, без числа по прямій. " +
            "kind: лікарня (типово) / клініка / аптека; radius_m — радіус пошуку (типово 15000); " +
            "profile: авто (типово) / пішки. Завжди повертає MGRS + плюс-код + орієнтир для передачі стороннім."

    override val argsSchema: JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("string?"))
        put("radius_m", JsonPrimitive("number?"))
        put("profile", JsonPrimitive("string?"))
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = medevacManifest("tool.medevac.nearest", "Медевак: найближче за дорогою")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start

        val argLat = args["lat"]?.jsonPrimitive?.doubleOrNull
        val argLon = args["lon"]?.jsonPrimitive?.doubleOrNull
        val fix = runCatching { locationProvider?.current() }.getOrNull()
        val lat = argLat ?: fix?.lat
        val lon = argLon ?: fix?.lon
        if (lat == null || lon == null) {
            return ToolResult.Err(
                "no_location",
                "нічого не шукав: невідома позиція. Без неї немає ні «найближчого», ні картки передачі. " +
                    "Передай lat/lon пораненого або увімкни геолокацію і повтори.",
                dur(),
            )
        }
        // A passed-in point is not the device's fix: its accuracy is unknown,
        // and precisionFor() must be told so rather than inheriting the fix's.
        val accuracyM = if (argLat != null || argLon != null) null else fix?.accuracyM

        val kind = resolveMedevacKind(args["kind"]?.jsonPrimitive?.content)
        val radius = (args["radius_m"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 15_000).coerceIn(500, 60_000)
        // A casualty run drives; the shared resolver defaults to FOOT.
        val profileArg = args["profile"]?.jsonPrimitive?.content
        val profile = if (profileArg == null) RouteProfile.CAR else resolveProfile(profileArg)

        val card = handoverCardFor(lat, lon, accuracyM, findPois)
        val found = runCatching { findPois(lat, lon, radius, kind.selector) }.getOrDefault(emptyList())

        if (found.isEmpty()) {
            val wider = (radius * 2).coerceAtMost(60_000)
            val nextStep = if (radius < 60_000) {
                "Розшир пошук: radius_m=$wider. Якщо й там порожньо — у цих даних мапи закладу немає, " +
                    "і це не доказ, що його немає в реальності."
            } else {
                "Радіус уже максимальний (60 км) — у цих даних мапи закладу немає. " +
                    "Передай картку нижче тому, хто знає місцевість."
            }
            return ToolResult.Ok(
                output = buildString {
                    append("«${kind.label}» в радіусі $radius м не знайдено — жодного кандидата, тому й маршруту немає.\n")
                    append("$nextStep\n")
                    append("Картка передачі позиції (передай тому, хто має транспорт):\n")
                    append(card.lines().joinToString("\n"))
                },
                structured = buildJsonObject {
                    put("routed", false)
                    put("refusal", "no_candidates")
                    put("kind", kind.label)
                    put("candidates", 0)
                    put("radius_m", radius)
                    put("lat", lat)
                    put("lon", lon)
                    put("next_step", nextStep)
                    put("source", "osm_poi_store")
                    put("absence_means", "немає в даних мапи, не доказ відсутності")
                    put("handover", card.toJson())
                },
                durationMs = dur(),
            )
        }

        val candidates = found
            .sortedBy { greatCircleMeters(lat, lon, it.lat, it.lon) }
            .take(MEDEVAC_CANDIDATE_CAP)
        val pick = pickRoutedNearest(profile, lat, lon, candidates, routeMatrix, routeLeg)
        val winner = pick.index?.let { candidates[it] }
        val leg = winner?.let { routeLeg(profile, lat, lon, it.lat, it.lon) }

        if (winner == null || leg == null) {
            val why = if (pick.routerAnswered) {
                "маршрутизатор відповів, але жодного проїзного шляху до ${candidates.size} кандидатів немає"
            } else {
                "маршрутизатор не відповів — чи дорога є, невідомо"
            }
            val nextStep = if (pick.routerAnswered) {
                "Дороги туди немає для профілю «${profile.label}» — спробуй profile=пішки, " +
                    "інший kind, або передай картку нижче тому, хто знає обʼїзд."
            } else {
                "Схоже, немає звʼязку з маршрутизатором. Повтори, коли буде звʼязок; " +
                    "поки що передай картку нижче тому, хто має транспорт."
            }
            return ToolResult.Ok(
                output = buildString {
                    append("МАРШРУТУ НЕМАЄ до «${kind.label}» ($why).\n")
                    append("Відстань по прямій НЕ називаю — вона не є відстанню, яку хтось проїде.\n")
                    append("Знайдено кандидатів у радіусі $radius м: ${found.size}.\n")
                    append("$nextStep\n")
                    append("Картка передачі позиції:\n")
                    append(card.lines().joinToString("\n"))
                },
                structured = buildJsonObject {
                    put("routed", false)
                    put("refusal", "no_route")
                    put("kind", kind.label)
                    put("router_answered", pick.routerAnswered)
                    put("candidates", found.size)
                    put("candidates_asked", pick.askedCount)
                    put("radius_m", radius)
                    put("profile", profile.slug)
                    put("lat", lat)
                    put("lon", lon)
                    put("next_step", nextStep)
                    put("handover", card.toJson())
                },
                durationMs = dur(),
            )
        }

        val straightM = greatCircleMeters(lat, lon, winner.lat, winner.lon)
        val line = SpatialLine(
            points = leg.points.map { GeoPoint(it.first, it.second) },
            color = "#EF4444",
            width = 7f,
            label = "медевак: ${winner.name} (${fmtKmMedevac(leg.distanceMeters)} · ${fmtMinMedevac(leg.durationSeconds)})",
        )
        val layerId = sink.push(
            SpatialLayerSpec(
                title = "Медевак: ${winner.name}",
                origin = LayerOrigin.WORLD,
                kind = "medevac_route",
                ownerThreadId = currentThreadId(),
                markers = listOf(
                    SpatialMarker(lat, lon, "Позиція: ${card.mgrs ?: "—"}"),
                    SpatialMarker(winner.lat, winner.lon, winner.name, color = "#EF4444"),
                ),
                lines = listOf(line),
                color = "#EF4444",
                ttlMs = 60 * 60_000L,
            ),
        )
        runCatching { sink.requestFocus(FocusTarget((lat + winner.lat) / 2, (lon + winner.lon) / 2, layerId = layerId)) }
        requestMapOpen()

        val address = osmAddress(winner.tags)
        val phone = winner.tags["phone"] ?: winner.tags["contact:phone"]
        return ToolResult.Ok(
            output = buildString {
                append("Найближче ЗА ДОРОГОЮ (${profile.label}): «${winner.name}» — ")
                append("${fmtKmMedevac(leg.distanceMeters)}, ${fmtMinMedevac(leg.durationSeconds)}.\n")
                address?.let { append("Адреса: $it.\n") }
                phone?.let { append("Телефон з даних мапи (може бути застарілий): $it.\n") }
                append("${erLine(winner.tags)}.\n")
                append("Обрано за часом у дорозі серед ${pick.askedCount} кандидатів ")
                append("(маршрут дали ${pick.routedCount}); всього знайдено ${found.size} в радіусі $radius м. ")
                append("По прямій ${fmtKmMedevac(straightM)} — це НЕ шлях, лише для орієнтації.\n")
                append("Заклади далі за $radius м не розглядались.\n")
                append("Картка передачі позиції:\n")
                append(card.lines().joinToString("\n"))
            },
            structured = buildJsonObject {
                put("routed", true)
                put("layer_id", layerId)
                put("name", winner.name)
                put("kind", kind.label)
                address?.let { put("address", it) }
                phone?.let { put("phone", it) }
                put("lat", winner.lat)
                put("lon", winner.lon)
                put("routed_distance_m", leg.distanceMeters)
                put("routed_duration_s", leg.durationSeconds)
                put("straight_line_m", straightM)
                put("profile", profile.slug)
                put("candidates", found.size)
                put("candidates_asked", pick.askedCount)
                put("candidates_routed", pick.routedCount)
                put("radius_m", radius)
                put("er_tagged", winner.tags["emergency"] == "yes")
                // Positive-only: absence of the tag is absence of data, and the
                // card must not be able to render it as «немає приймального».
                put("er_claim", if (winner.tags["emergency"] == "yes") "позначено" else "невідомо")
                put("handover", card.toJson())
                put("ranked_by", "routed_duration")
                put("source", "osm_poi_store + osrm_routed")
            },
            durationMs = dur(),
        )
    }
}

/** `вул. Шевченка, 12` out of whatever addr:* the extract happens to carry. */
internal fun osmAddress(tags: Map<String, String>): String? {
    val street = tags["addr:street"]?.trim()?.takeIf { it.isNotEmpty() }
    val house = tags["addr:housenumber"]?.trim()?.takeIf { it.isNotEmpty() }
    val city = tags["addr:city"]?.trim()?.takeIf { it.isNotEmpty() }
    val parts = listOfNotNull(
        street?.let { s -> if (house != null) "$s, $house" else s },
        city,
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/**
 * `medevac.handover` — F-08 alone: the position in three registers, for a
 * stranger with no app. Needs no routing and no facility data.
 */
class HandoverCardTool(
    private val findPois: PoiLookupFn,
    private val locationProvider: LocationContextProvider?,
) : Tool {

    override val id = "medevac.handover"

    override val description =
        "Картка передачі позиції по радіо: MGRS + плюс-код (Plus Code) + орієнтир «скільки км і в який бік від якого села». " +
            "Уживай, коли треба продиктувати позицію тому, у кого немає застосунку — диспетчеру, блокпосту, водію. " +
            "Без аргументів бере поточну позицію; lat/lon — передати чужу точку."

    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.OUTWARD // картка складається, щоб піти іншому підрозділу
    override val askEveryCall: Boolean = true
    override val streaming: Boolean = false
    override val manifest = medevacManifest("tool.medevac.handover", "Картка передачі позиції")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val argLat = args["lat"]?.jsonPrimitive?.doubleOrNull
        val argLon = args["lon"]?.jsonPrimitive?.doubleOrNull
        val fix = runCatching { locationProvider?.current() }.getOrNull()
        val lat = argLat ?: fix?.lat
        val lon = argLon ?: fix?.lon
        if (lat == null || lon == null) {
            return ToolResult.Err(
                "no_location",
                "картки немає: невідома позиція. Диктувати нічого. Увімкни геолокацію і дай приймачу " +
                    "хвилину під відкритим небом, або передай lat/lon точки, яку треба продиктувати.",
                System.currentTimeMillis() - start,
            )
        }
        val accuracyM = if (argLat != null || argLon != null) null else fix?.accuracyM
        val card = handoverCardFor(lat, lon, accuracyM, findPois)
        return ToolResult.Ok(
            output = card.lines().joinToString("\n"),
            structured = buildJsonObject {
                put("handover", card.toJson())
                put("lines", buildJsonArray { card.lines().forEach { add(it) } })
                put("lat", lat)
                put("lon", lon)
                put("position_source", if (argLat != null || argLon != null) "аргументи виклику" else "поточний фікс")
                put("mgrs_precision_m", card.precision.mgrsMeters)
                put("plus_code_precision_m", card.precision.plusCodeMeters)
                put("settlement_known", card.settlement != null)
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}
