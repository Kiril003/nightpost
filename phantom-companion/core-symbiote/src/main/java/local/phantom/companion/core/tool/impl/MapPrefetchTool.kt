package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * ПРОВИДЕЦЬ — `map.prefetch`: pre-pull map tiles + the permanent road mesh
 * along a corridor so navigation survives the connection dying mid-drive.
 * The host owns routing/geocoding/prefetching; the tool only decides intent.
 */
class MapPrefetchTool(
    private val resolvePlace: suspend (String) -> Pair<Double, Double>?,
    /** dest == null → prefetch the active route's corridor. Returns the report line. */
    private val launchCorridor: suspend (dest: Pair<Double, Double>?, label: String?, tiles: Boolean) -> String,
    private val progress: suspend () -> String,
) : Tool {

    override val id = "map.prefetch"

    override val description =
        "Превентивний кеш коридору: заздалегідь стягнути тайли мапи й дорожню сітку вздовж " +
            "маршруту до цілі, щоб навігація пережила зникнення звʼязку (поїздка в інше місто, " +
            "зона глушіння). place — назва цілі, або lat+lon; без цілі — коридор активного " +
            "маршруту; зовсім без аргументів — стан поточного стягування. tiles=false — лише " +
            "дорожня сітка, без тайлів."

    override val argsSchema: JsonObject = buildJsonObject {
        put("place", "string?")
        put("lat", "number?")
        put("lon", "number?")
        put("tiles", "bool?")
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK)
    override val risk: ToolRisk = ToolRisk.OUTWARD // без аргументів лише статус, з аргументами качає тайли
    override val askEveryCall: Boolean = true
    override val streaming: Boolean = false
    override val manifest = FeatureManifest(
        id = "tool.map.prefetch",
        tier = 0,
        displayName = "Превентивний кеш",
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
        if (args.isEmpty()) {
            val line = runCatching { progress() }.getOrElse {
                return ToolResult.Err(
                    "progress_unavailable",
                    "стан стягування не читається: ${it.message ?: "менеджер кешу не відповів"}. " +
                        "Запусти стягування наново — map.prefetch з ціллю.",
                    dur(),
                )
            }
            return ToolResult.Ok(
                line,
                structured = buildJsonObject {
                    put("mode", "стан")
                    put("report", line)
                },
                durationMs = dur(),
            )
        }

        val place = args["place"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val lat = args["lat"]?.jsonPrimitive?.doubleOrNull
        val lon = args["lon"]?.jsonPrimitive?.doubleOrNull
        val tiles = args["tiles"]?.jsonPrimitive?.booleanOrNull ?: true

        val dest: Pair<Double, Double>? = when {
            lat != null && lon != null -> lat to lon
            place != null -> resolvePlace(place)
                ?: return ToolResult.Err(
                    "place_not_found",
                    "нічого не стягував: «$place» геокодер не впізнав (або геокодера немає в цій збірці). " +
                        "Передай lat/lon цілі напряму — тоді коридор будується без пошуку назви.",
                    dur(),
                )
            else -> null
        }

        val report = runCatching { launchCorridor(dest, place, tiles) }.getOrElse {
            return ToolResult.Err(
                "prefetch_failed",
                "стягування не стартувало: ${it.message ?: "менеджер кешу відмовив"}. " +
                    "Найчастіше це відсутність звʼязку або брак місця на пристрої — перевір і повтори.",
                dur(),
            )
        }
        return ToolResult.Ok(
            report,
            // Was null: nothing downstream could tell a corridor prefetch from a
            // status read, or say what was actually asked for.
            structured = buildJsonObject {
                put("mode", if (dest == null) "коридор активного маршруту" else "коридор до цілі")
                dest?.let { put("dest_lat", it.first); put("dest_lon", it.second) }
                place?.let { put("place", it) }
                put("dest_source", if (lat != null && lon != null) "координати" else if (place != null) "геокодер" else "активний маршрут")
                put("tiles", tiles)
                put("report", report)
                put("started", true)
            },
            durationMs = dur(),
        )
    }
}
