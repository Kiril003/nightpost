package local.phantom.companion.core.geo.metro

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * `metro.station` — схема станції метро на мапу, викликана голосом або з чату.
 *
 * ЧОМУ ІНСТРУМЕНТ, А НЕ КНОПКА. Продукт побудований навколо діалогу: те, що
 * оператор питає словами, виконує інструмент, а мапа оживає як наслідок. Питання
 * «яким виходом ближче», «чи є там ліфт», «як пройти на платформу» — рівно того
 * типу, який не має власної кнопки і не повинен її мати: під землею, у натовпі
 * або з валізою в руці людина говорить, а не шукає чип у меню. Той самий шлях
 * уже пройдено для транспорту, камер і літаків (`world.*`), тож і шар метро
 * приходить на мапу тим самим конвеєром — з чипом, фокусом і TTL.
 *
 * ЧОМУ ЦЕЙ ФАЙЛ У `:core-geo`. Це єдиний адаптер: уся логіка живе в
 * [MetroStationLayer], який не знає ні про `Tool`, ні про промпт. Якщо
 * залежність `:core-geo → :core-symbiote` колись стане небажаною, видалення
 * САМЕ ЦЬОГО файла знімає її повністю і нічого більше не ламає — шов проведено
 * тут навмисно.
 *
 * ЧЕСНІСТЬ. Інструмент ніколи не вигадує станцію: якщо Overpass мовчить — це
 * `Err` зі словом «звʼязок», якщо OSM порожній — це `Ok` зі словом «немає
 * даних», і ці дві відповіді ніколи не підмінюють одна одну.
 */
class MetroStationTool internal constructor(
    private val layer: MetroStationLayer,
    private val currentLocation: (suspend () -> Pair<Double, Double>?)?,
) : Tool {

    /**
     * Бойовий конструктор — той, що вставляється в реєстр інструментів.
     *
     * @param currentLocation поточна позиція оператора: запасний варіант, коли
     *   модель не передала координат. `null` означає «геолокації немає» —
     *   тоді інструмент чесно просить координати, а не бере навмання центр міста.
     */
    @JvmOverloads
    constructor(
        sink: SpatialLayerSink,
        currentLocation: (suspend () -> Pair<Double, Double>?)? = null,
        currentThreadId: () -> String? = { null },
    ) : this(MetroStationLayer(sink, currentThreadId), currentLocation)

    override val id: String = "metro.station"

    override val description: String =
        "Схема станції метро з OSM/OpenStationMap: входи з номерами на табличках, доступність " +
            "у візку, платформи і пішохідний маршрут усередині станції (сходи, ескалатори з " +
            "напрямком руху, ліфти). Кладе шар «метро» на мапу і наводить камеру на найближчий " +
            "вхід. Аргумент wheelchair=true будує ЛИШЕ безбарʼєрний маршрут. Показує тільки те, " +
            "що є в OSM — не вигадуй входи, ліфти чи номери виходів."

    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("radius_m", JsonPrimitive("number?"))
        put("wheelchair", JsonPrimitive("boolean?"))
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest: FeatureManifest = FeatureManifest(
        id = "tool.metro.station",
        tier = 1,
        displayName = "Метро — схема станції",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 90,
        surfaces = setOf(FeatureManifest.Surface.STREAM, FeatureManifest.Surface.MAP),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val coords = resolveCoords(args)
            ?: return ToolResult.Err(
                code = "no_location",
                message = "Немає координат — передай lat/lon або увімкни геолокацію.",
                durationMs = System.currentTimeMillis() - start,
            )
        val radius = args["radius_m"]?.jsonPrimitive?.intOrNull ?: DEFAULT_RADIUS_M
        val wheelchair = args["wheelchair"]?.jsonPrimitive?.booleanOrNull ?: false

        val result = layer.summon(
            latDeg = coords.first,
            lonDeg = coords.second,
            radiusM = radius,
            requireWheelchair = wheelchair,
        )
        val took = System.currentTimeMillis() - start

        return when (result) {
            is MetroStationResult.Mapped -> ToolResult.Ok(
                output = result.summary,
                structured = buildJsonObject {
                    put("layer_id", result.layerId)
                    result.stationName?.let { put("station", it) }
                    put("entrances", result.entranceCount)
                    put("accessible_entrances", result.accessibleEntranceCount)
                    result.nearestEntranceRef?.let { put("nearest_ref", it) }
                    put("nearest_distance_m", result.nearestEntranceDistanceM)
                    result.routeSeconds?.let { put("route_seconds", it) }
                    result.routeLengthM?.let { put("route_length_m", it) }
                    put("route_uses_elevator", result.routeUsesElevator)
                    put("wheelchair_route", result.wheelchairRouteExists)
                    put("attribution", result.attribution)
                },
                durationMs = took,
            )

            // Порожньо — це відповідь, а не збій: Overpass відпрацював, у даних
            // нічого немає. Помилкою це робити не можна, бо модель почне
            // перепитувати мережу замість того, щоб сказати правду.
            is MetroStationResult.NoIndoorData -> ToolResult.Ok(
                output = result.summary,
                structured = buildJsonObject {
                    put("entrances", 0)
                    put("radius_m", result.radiusM)
                },
                durationMs = took,
            )

            is MetroStationResult.Unavailable ->
                ToolResult.Err("geo_unavailable", result.summary, took)

            is MetroStationResult.LicenseBlocked ->
                ToolResult.Err("license_blocked", result.summary, took)
        }
    }

    /** Координати з аргументів, інакше — поточна позиція оператора. */
    private suspend fun resolveCoords(args: JsonObject): Pair<Double, Double>? {
        val lat = args["lat"]?.jsonPrimitive?.doubleOrNull
        val lon = args["lon"]?.jsonPrimitive?.doubleOrNull
        if (lat != null && lon != null) return lat to lon
        return runCatching { currentLocation?.invoke() }.getOrNull()
    }

    private companion object {
        const val DEFAULT_RADIUS_M = 300
    }
}
