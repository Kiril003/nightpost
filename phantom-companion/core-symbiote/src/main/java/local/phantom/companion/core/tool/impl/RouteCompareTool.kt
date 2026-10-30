package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import local.phantom.companion.core.net.map.RouteIntel
import local.phantom.companion.core.net.map.RouteIntelReport
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import local.phantom.companion.core.net.map.RiskTier
import local.phantom.companion.core.net.map.riskTier
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.util.Locale
import kotlin.math.roundToInt

/**
 * NAV-1 — `route.compare`: the same corridor depth the operator gets in
 * the compare sheet, as a tool-verb (Law 2 — dual control). Fetches OSRM
 * alternatives, runs [RouteIntel] over each (road mix, lighting, speed
 * cameras, ascent), draws every variant on the live map (chosen bright,
 * alternatives dim) and answers with an honest side-by-side digest. A
 * variant whose corridor analysis failed says so — no invented facts.
 *
 * The answer carries `results` as well as `variants`: `results` is the
 * shape the card layer reads, and without it a comparison this expensive
 * reached the operator as a paragraph of prose and nothing else.
 */
class RouteCompareTool(
    private val sink: SpatialLayerSink,
    private val locationProvider: LocationContextProvider?,
    private val currentThreadId: () -> String?,
    private val osrm: OsrmClient = OsrmClient(),
    private val intel: RouteIntel = RouteIntel(),
    private val requestMapOpen: () -> Unit = {},
) : Tool {

    override val id = "route.compare"

    override val description =
        "Порівняти ВАРІАНТИ маршруту з глибоким аналізом коридору: час, відстань, повороти, склад доріг " +
            "(магістраль/житлова/ґрунт), освітлення, камери швидкості, набір висоти. Малює всі варіанти на " +
            "живій мапі (обраний яскравий, альтернативи тьмяні) і виводить мапу на екран. Уживай, коли оператор " +
            "питає «як краще доїхати», «які варіанти», «порівняй маршрути» — для простого одного маршруту є " +
            "map.route. Профіль: пішки (типово) / авто / вело. from за замовчуванням — локація оператора. " +
            "Час — оцінка OSRM, не гарантія; ризик рахується ЛИШЕ по коридору, тривог цей інструмент не бачить."

    override val argsSchema: JsonObject = buildJsonObject {
        put("to_lat", JsonPrimitive("number"))
        put("to_lon", JsonPrimitive("number"))
        put("from_lat", JsonPrimitive("number?"))
        put("from_lon", JsonPrimitive("number?"))
        put("profile", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = FeatureManifest(
        id = "tool.route.compare",
        tier = 0,
        displayName = "Порівняння маршрутів",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 90,
        surfaces = setOf(FeatureManifest.Surface.STREAM),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val toLat = args["to_lat"]?.jsonPrimitive?.doubleOrNull
        val toLon = args["to_lon"]?.jsonPrimitive?.doubleOrNull
        if (toLat == null || toLon == null) {
            return ToolResult.Err(
                "bad_args",
                "Не знаю, куди порівнювати дорогу: потрібні to_lat і to_lon у десяткових градусах. " +
                    "Знайди точку (map.search або place.list) і передай її координати.",
                dur(),
            )
        }
        val fromLat = args["from_lat"]?.jsonPrimitive?.doubleOrNull
        val fromLon = args["from_lon"]?.jsonPrimitive?.doubleOrNull
        val (lat0, lon0) = if (fromLat != null && fromLon != null) fromLat to fromLon
        else runCatching { locationProvider?.current() }.getOrNull()?.let { it.lat to it.lon }
            ?: return ToolResult.Err(
                "no_location",
                "Не знаю, звідки рахувати: власної локації зараз немає. " +
                    "Передай from_lat/from_lon або дай Nightpost доступ до геолокації і повтори.",
                dur(),
            )
        if (outOfRange(lat0, lon0) || outOfRange(toLat, toLon)) {
            return ToolResult.Err(
                "bad_coords",
                "Координати поза мапою: широта −90…90, довгота −180…180. " +
                    "Схоже, широту й довготу переставлено місцями — передай ще раз, широта перша.",
                dur(),
            )
        }
        val profileWord = args["profile"]?.jsonPrimitive?.content?.lowercase()?.trim()
        val profile = when (profileWord) {
            "авто", "car", "driving", "машина", "машиною" -> RouteProfile.CAR
            "вело", "bike", "cycling", "велосипед", "велосипедом" -> RouteProfile.BIKE
            null, "", "пішки", "пішком", "walk", "walking", "foot" -> RouteProfile.FOOT
            else -> return ToolResult.Err(
                "bad_args",
                "Способу пересування «$profileWord» не існує. Є пішки, авто, вело — назви один із них.",
                dur(),
            )
        }

        val variants = runCatching { osrm.routeDetailed(profile, listOf(lat0 to lon0, toLat to toLon)) }
            .getOrNull()
            ?.take(MAX_VARIANTS)
            ?: return ToolResult.Err(
                "routing_unavailable",
                "Маршрут не склався: сервер маршрутизації не відповів або дороги між цими точками немає " +
                    "(${profile.label}). Скажи це оператору прямо — вигаданого маршруту не буде. " +
                    "Можна спробувати інший профіль або ближчу точку.",
                dur(),
            )
        if (variants.isEmpty()) {
            return ToolResult.Err(
                "routing_unavailable",
                "Сервер маршрутизації не дав жодного варіанта для профілю «${profile.label}». " +
                    "Спробуй інший профіль або ближчу точку.",
                dur(),
            )
        }

        // One corridor at a time — Overpass and open-elevation are shared
        // public instances; a failed analysis stays an honest null.
        val reports: List<RouteIntelReport?> = variants.map { v ->
            runCatching { intel.analyze(v.points) }.getOrNull()
        }

        // NAV-Ω1 — corridor-only risk (this verb has no alert-feed access;
        // the factor list makes the basis explicit) + the ghost variant.
        val risks = variants.mapIndexed { i, v ->
            local.phantom.companion.core.net.map.assessRouteRisk(reports[i], alertCrossings = 0, profile = v.profile)
        }
        val ghostIdx = local.phantom.companion.core.net.map.ghostVariantIndex(
            variants.mapIndexed { i, v -> v to reports[i] },
        )

        // Alternatives first (dim), the chosen corridor last so it draws on top.
        val lines = variants.mapIndexed { i, v ->
            SpatialLine(
                points = v.points.map { GeoPoint(it.first, it.second) },
                color = if (i == 0) "#3B82F6" else "#64748B",
                width = if (i == 0) 6f else 4f,
                label = "варіант ${letter(i)}: ${fmtKmC(v.distanceMeters)} · ${fmtMinC(v.durationSeconds)}",
            )
        }
        val spec = SpatialLayerSpec(
            title = "варіанти маршруту (${variants.size}, ${profile.label})",
            origin = LayerOrigin.WORLD,
            kind = "route_compare",
            ownerThreadId = currentThreadId(),
            markers = listOf(
                SpatialMarker(lat0, lon0, "старт"),
                SpatialMarker(toLat, toLon, "фініш"),
            ),
            lines = lines.drop(1) + lines.first(),
            color = "#3B82F6",
            ttlMs = 60 * 60_000L,
        )
        val layerId = sink.push(spec)
        runCatching {
            sink.requestFocus(FocusTarget((lat0 + toLat) / 2, (lon0 + toLon) / 2, layerId = layerId))
        }
        requestMapOpen()

        val digest = variants.mapIndexed { i, v -> variantLine(i, v, reports[i], risks[i], ghostIdx) }
            .joinToString("\n")

        return ToolResult.Ok(
            output = buildString {
                if (variants.size == 1) {
                    append("Сервер дав лише один варіант (${profile.label}).\n")
                } else {
                    append("Порівняв ${variants.size} варіанти (${profile.label}), ")
                    append("показав усі на мапі — обраний яскравий.\n")
                }
                append(digest)
                append("\nЧас — оцінка, не гарантія. Ризик — лише по коридору: тривог цей інструмент не бачить.")
            },
            structured = buildJsonObject {
                put("layer_id", layerId)
                put("profile", profile.slug)
                put("profile_label", profile.label)
                put("variant_count", variants.size)
                put("source", "OSRM — маршрути й час; OSM/Overpass — коридор; висоти — open-elevation")
                put("certainty", "час — оцінка, не гарантія")
                put("risk_basis", "лише коридор — активних тривог цей інструмент не бачить")
                // Те, що читає картковий шар: без цього масиву порівняння
                // діставалось оператору лише прозою.
                put(
                    "results",
                    buildJsonArray {
                        variants.forEachIndexed { i, v ->
                            add(
                                buildJsonObject {
                                    put(
                                        "title",
                                        "Варіант ${letter(i)}" + if (i == 0) " — обраний" else "",
                                    )
                                    put("snippet", variantSnippet(v, reports[i], risks[i], i == ghostIdx))
                                },
                            )
                        }
                    },
                )
                put(
                    "variants",
                    buildJsonArray {
                        variants.forEachIndexed { i, v ->
                            add(
                                buildJsonObject {
                                    put("letter", letter(i))
                                    put("chosen", i == 0)
                                    put("distance_m", v.distanceMeters)
                                    put("duration_s", v.durationSeconds)
                                    put("turns", v.turnCount)
                                    val r = reports[i]
                                    if (r != null && r.coverage >= MIN_COVERAGE) {
                                        put("roads", r.classShares.take(3).joinToString(" · ") { (c, s) -> "$c ${pctC(s)}" })
                                        put("cameras", r.cameras.size)
                                        put("corridor_coverage_pct", (r.coverage * 100).roundToInt())
                                        r.litFraction?.let { put("lit_pct", (it * 100).roundToInt()) }
                                        r.ascentMeters?.let { put("ascent_m", it.roundToInt()) }
                                        r.descentMeters?.let { put("descent_m", it.roundToInt()) }
                                    } else {
                                        put("corridor_data", "аналіз коридору недоступний")
                                    }
                                    val risk = risks[i]
                                    if (risk != null) {
                                        put("risk_score", risk.score)
                                        put("risk_scale", "0–100, більше — ризикованіше")
                                        put("risk_tier", tierWord(riskTier(risk.score)))
                                        put("risk_factors", risk.factors.joinToString("; ") { it.first })
                                    } else {
                                        put("risk", "не оцінював — немає на чому")
                                    }
                                    if (i == ghostIdx) put("ghost", "тихий хід")
                                },
                            )
                        }
                    },
                )
            },
            durationMs = dur(),
        )
    }

    private fun variantLine(
        i: Int,
        v: RouteVariant,
        r: RouteIntelReport?,
        risk: local.phantom.companion.core.net.map.RouteRiskReport?,
        ghostIdx: Int?,
    ): String =
        "Варіант ${letter(i)}${if (i == 0) " (обрав його)" else ""}" +
            (if (i == ghostIdx) " · тихий хід" else "") + ": " +
            "${fmtKmC(v.distanceMeters)} · ${fmtMinC(v.durationSeconds)} · ${v.turnCount} поворотів" +
            describeIntel(r) +
            (
                risk?.let {
                    " · ризик ${it.score}/100 (${tierWord(riskTier(it.score))})" +
                        if (it.factors.isNotEmpty()) ": ${it.factors.take(2).joinToString(", ") { f -> f.first }}" else ""
                } ?: " · ризик не оцінював — немає на чому"
                )

    private fun variantSnippet(
        v: RouteVariant,
        r: RouteIntelReport?,
        risk: local.phantom.companion.core.net.map.RouteRiskReport?,
        ghost: Boolean,
    ): String = buildList {
        add(fmtKmC(v.distanceMeters))
        add(fmtMinC(v.durationSeconds))
        add("${v.turnCount} поворотів")
        if (r != null && r.coverage >= MIN_COVERAGE) {
            r.litFraction?.let { add("освітлено ${pctC(it)}") }
            add(if (r.cameras.isEmpty()) "камер немає" else "камер ${r.cameras.size}")
        } else {
            add("коридор невідомий")
        }
        if (risk != null) add("ризик ${risk.score}/100 · ${tierWord(riskTier(risk.score))}")
        else add("ризик не оцінено")
        if (ghost) add("тихий хід")
    }.joinToString(" · ")

    private fun describeIntel(r: RouteIntelReport?): String {
        if (r == null || r.coverage < MIN_COVERAGE) return " · аналіз коридору недоступний"
        val parts = buildList {
            r.classShares.take(2).takeIf { it.isNotEmpty() }?.let { shares ->
                add(shares.joinToString(", ") { (c, s) -> "$c ${pctC(s)}" })
            }
            r.surfaceShares.firstOrNull { it.first == "ґрунт/гравій" }?.let { add("ґрунт ${pctC(it.second)}") }
            r.litFraction?.let { add("освітлено ${pctC(it)}") }
            add(if (r.cameras.isEmpty()) "камер немає" else "камери: ${r.cameras.size}")
            if (r.ascentMeters != null) add("підйом ${r.ascentMeters!!.roundToInt()} м")
        }
        return " · " + parts.joinToString(" · ")
    }

    private companion object {
        const val MAX_VARIANTS = 3
        const val MIN_COVERAGE = 0.33
        fun letter(i: Int) = listOf("А", "Б", "В", "Г").getOrElse(i) { "${i + 1}" }
        fun pctC(f: Double) = "${(f * 100).roundToInt()}%"
        fun outOfRange(lat: Double, lon: Double) = lat !in -90.0..90.0 || lon !in -180.0..180.0
        fun tierWord(tier: RiskTier) = when (tier) {
            RiskTier.LOW -> "низький"
            RiskTier.ELEVATED -> "підвищений"
            RiskTier.HIGH -> "високий"
        }
        fun fmtKmC(m: Double) =
            if (m >= 1000) String.format(Locale.US, "%.1f км", m / 1000.0) else "${m.toInt()} м"
        fun fmtMinC(s: Double): String {
            val min = (s / 60.0).toInt()
            return if (min < 60) "$min хв" else String.format(Locale.US, "%dг %02dхв", min / 60, min % 60)
        }
    }
}

/** NAV-1 — shared "lat,lon;lat,lon" via-waypoint parsing for route tools. */
internal fun parseViaWaypoints(raw: String?): List<Pair<Double, Double>> =
    raw.orEmpty().split(";").mapNotNull { pair ->
        val p = pair.split(",")
        val lat = p.getOrNull(0)?.trim()?.toDoubleOrNull()
        val lon = p.getOrNull(1)?.trim()?.toDoubleOrNull()
        if (lat != null && lon != null) lat to lon else null
    }
