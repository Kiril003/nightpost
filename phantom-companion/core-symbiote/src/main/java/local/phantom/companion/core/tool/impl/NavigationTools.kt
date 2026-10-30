package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * NAV-2 — live turn-by-turn guidance as tool-verbs (Law 2 — dual control:
 * the same flow the operator's «старт» tap drives). The tools don't route
 * anything themselves — they hand the request to the open map surface
 * (via AppContainer's nav-command flow) and bring the map on screen; the
 * map plans through the same RoutePlanner/GuidanceController code path
 * the finger uses, and speaks honestly if routing fails.
 *
 * Nothing here may report a distance or an ETA: at the moment these verbs
 * return, OSRM has not answered yet. They report the handover and say so.
 */

private fun navManifest(id: String, name: String) = FeatureManifest(
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

private data class NavProfile(val slug: String, val label: String)

/** Порожній профіль = пішки. Невідоме слово — не мовчазна заміна, а відмова. */
private fun resolveNavProfile(raw: String?): NavProfile? {
    val word = raw?.lowercase()?.trim()
    if (word.isNullOrEmpty()) return NavProfile("пішки", "пішки")
    return when (word) {
        "пішки", "пішо", "пішком", "walk", "walking", "foot", "on foot" -> NavProfile("пішки", "пішки")
        "авто", "автом", "машина", "машиною", "car", "driving", "drive" -> NavProfile("авто", "автом")
        "вело", "велосипед", "велосипедом", "bike", "cycling", "bicycle" -> NavProfile("вело", "велосипедом")
        else -> null
    }
}

/** Найчастіша помилка моделі — переставлені місцями широта й довгота. */
private fun coordProblem(lat: Double, lon: Double): String? = when {
    lat !in -90.0..90.0 ->
        "широта $lat поза межами −90…90 — схоже, широту й довготу переставлено місцями"
    lon !in -180.0..180.0 ->
        "довгота $lon поза межами −180…180 — схоже, широту й довготу переставлено місцями"
    else -> null
}

private fun JsonObject.coord(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

/** `nav.start` — begin live guidance to a point, voice supervision included. */
class NavStartTool(
    private val requestNavStart: (
        toLat: Double,
        toLon: Double,
        profile: String?,
        via: List<Pair<Double, Double>>,
    ) -> Unit,
    private val http: GeoHttp? = null,
    private val requestMapOpen: () -> Unit = {},
) : Tool {

    override val id = "nav.start"

    override val description =
        "Запустити ЖИВУ покрокову навігацію до точки (як у навігатора): маршрут на мапі, камера-переслідування, " +
            "маневр-банер, голосовий супровід українською, авто-перемаршрут якщо оператор зійшов з маршруту. " +
            "Виводить мапу на екран. Уживай, коли оператор просить ВЕСТИ його («веди мене», «поїхали до», " +
            "«навігація до») — для простого показу маршруту без супроводу є map.route. Приймає координати (to_lat/to_lon) " +
            "або назву/адресу (to/destination/address/query). Старт — поточна локація оператора. " +
            "Профіль: пішки (типово) / авто / вело. via — необовʼязкові проміжні точки формату \"lat,lon;lat,lon\". Зупинити — nav.stop."

    override val argsSchema: JsonObject = buildJsonObject {
        put("to", JsonPrimitive("string?"))
        put("to_lat", JsonPrimitive("number?"))
        put("to_lon", JsonPrimitive("number?"))
        put("destination", JsonPrimitive("string?"))
        put("address", JsonPrimitive("string?"))
        put("profile", JsonPrimitive("string?"))
        put("via", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val streaming: Boolean = false
    override val manifest = navManifest("tool.nav.start", "Жива навігація")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val rawLat = args.coord("to_lat") ?: args["to"]?.let { (it as? JsonObject)?.get("lat")?.jsonPrimitive?.doubleOrNull }
        val rawLon = args.coord("to_lon") ?: args["to"]?.let { (it as? JsonObject)?.get("lon")?.jsonPrimitive?.doubleOrNull }
        val toQuery = args["to"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: args["destination"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: args["address"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: args["query"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val resolved = resolveSmartCoordinate(rawLat, rawLon, toQuery, http)
        if (resolved == null) {
            return ToolResult.Err(
                "bad_args",
                "Не знаю, куди вести: " + smartCoordinateRefusal(toQuery),
                dur(),
            )
        }
        val (toLat, toLon) = resolved
        coordProblem(toLat, toLon)?.let {
            return ToolResult.Err(
                "bad_coords",
                "Точка призначення поза мапою: $it. Передай координати ще раз, широта перша.",
                dur(),
            )
        }
        val profile = resolveNavProfile(args["profile"]?.jsonPrimitive?.content ?: args["mode"]?.jsonPrimitive?.content)
            ?: return ToolResult.Err(
                "bad_args",
                "Такого способу пересування немає: «${args["profile"]?.jsonPrimitive?.content}». " +
                    "Є пішки, авто, вело — назви один із них. Мовчки вести пішки того, хто збирався їхати, я не буду.",
                dur(),
            )
        val via = parseViaWaypoints(args["via"]?.jsonPrimitive?.content)

        requestNavStart(toLat, toLon, profile.slug, via)
        requestMapOpen()

        return ToolResult.Ok(
            output = buildString {
                append("Веду ").append(profile.label).append(" — мапа на екрані, маршрут прокладається")
                if (via.isNotEmpty()) append(", через ${via.size} проміжні точки")
                if (toQuery != null) append(" до «$toQuery»")
                append(". Щойно маршрут складеться, голосовий штурман та банер візьмуть супровід.")
            },
            structured = buildJsonObject {
                put("to_lat", toLat)
                put("to_lon", toLon)
                if (toQuery != null) put("destination_name", toQuery)
                put("profile", profile.slug)
                put("profile_label", profile.label)
                put("via_count", via.size)
                put("state", "handed_to_map")
                put("route_known", false)
                put("source", "запит передано навігаційному рушію; маршрут рахує OSRM")
            },
            durationMs = dur(),
        )
    }
}

/** `nav.status` — telemetry of the currently active navigation session. */
data class NavTelemetrySnapshot(
    val isGuiding: Boolean,
    val destinationName: String? = null,
    val remainingDistanceM: Double? = null,
    val remainingDurationS: Double? = null,
    val currentSpeedKmh: Double? = null,
    val speedLimitKmh: Int? = null,
    val nextManeuverText: String? = null,
    /**
     * Threats counted on the corridor, or **null when nobody counted**.
     *
     * This was `Int = 0`, and the only wiring in the tree passed a hard `0`
     * that no scan produced. `threats_count: 0` then went to the model as
     * structured fact, and the tool's own description promises it means
     * «камери, КПП» — so the model read «checked, corridor clear» and had no
     * way to know otherwise. Every other field here was already absent when
     * unknown; this one alone asserted.
     *
     * A wrong temperature is a wrong claim about comfort. This is a claim
     * about **safety**, made to someone deciding whether to drive a corridor,
     * and «none found» is indistinguishable from «nobody looked» once it is a
     * number. Null is the only honest value until a real scan exists.
     */
    val activeThreatsCount: Int? = null,
    val curfewWarning: String? = null,
    /**
     * Whether curfew was actually consulted. Without this, `curfewWarning =
     * null` carries two opposite meanings — «no curfew in force» and «nobody
     * asked» — and silence reads as the reassuring one. Defaults to false
     * because nothing in the tree consults it yet.
     */
    val curfewChecked: Boolean = false,
)

class NavStatusTool(
    private val telemetryProvider: () -> NavTelemetrySnapshot,
) : Tool {

    override val id = "nav.status"

    override val description =
        "Отримати поточну телеметрію та статус живої навігації: чи активне ведення, ціль, залишок дистанції та часу, " +
            "поточна швидкість, обмеження швидкості, наступний маневр, загрози коридору (камери, КПП) та статус комендантської години."

    override val argsSchema: JsonObject = buildJsonObject {}
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = navManifest("tool.nav.status", "Статус навігації")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val snap = telemetryProvider()
        if (!snap.isGuiding) {
            return ToolResult.Ok(
                output = "Навігація наразі не активна. Вільний режим або рух без прокладеного маршруту.",
                structured = buildJsonObject { put("is_guiding", false) },
                durationMs = System.currentTimeMillis() - start,
            )
        }
        val distKm = snap.remainingDistanceM?.let { String.format(java.util.Locale.US, "%.1f км", it / 1000.0) } ?: "невідомо"
        val durMin = snap.remainingDurationS?.let { "${(it / 60).toInt()} хв" } ?: "невідомо"
        val spd = snap.currentSpeedKmh?.let { "${it.toInt()} км/год" } ?: "0 км/год"
        val out = buildString {
            append("Навігація активна")
            snap.destinationName?.let { append(" до «$it»") }
            append(": залишилося $distKm ($durMin). Швидкість $spd")
            snap.speedLimitKmh?.let { append(" (ліміт $it км/год)") }
            snap.nextManeuverText?.let { append(". Наступний маневр: $it") }
            // Silence is not neutral here. If the model is told nothing about
            // threats it fills the gap with the comfortable reading, so the
            // «not checked» case has to be said out loud, in words aimed at
            // the model's own behaviour.
            when {
                snap.activeThreatsCount == null ->
                    append(
                        ". Загрози коридору НЕ перевірялись — камери, КПП і подібне зараз невідомі. " +
                            "Не кажи, що їх немає; скажи, що це не перевірено"
                    )
                snap.activeThreatsCount > 0 ->
                    append(". Зафіксовано загроз на коридорі: ${snap.activeThreatsCount}")
                else ->
                    append(". Загроз на коридорі не зафіксовано")
            }
            when {
                snap.curfewWarning != null -> append(". Попередження: ${snap.curfewWarning}")
                !snap.curfewChecked ->
                    append(". Комендантську годину не перевірено — не стверджуй, що її немає")
            }
        }
        return ToolResult.Ok(
            output = out,
            structured = buildJsonObject {
                put("is_guiding", true)
                snap.destinationName?.let { put("destination", it) }
                snap.remainingDistanceM?.let { put("remaining_distance_m", it) }
                snap.remainingDurationS?.let { put("remaining_duration_s", it) }
                snap.currentSpeedKmh?.let { put("speed_kmh", it) }
                snap.speedLimitKmh?.let { put("speed_limit_kmh", it) }
                snap.nextManeuverText?.let { put("next_maneuver", it) }
                // Follow this file's own rule, which every other field already
                // obeys: publish a number only when someone produced it. An
                // absent key is a gap the model can notice; `0` is an answer
                // it cannot argue with.
                if (snap.activeThreatsCount != null) {
                    put("threats_count", snap.activeThreatsCount)
                } else {
                    put("threats_status", "не перевірено")
                }
                snap.curfewWarning?.let { put("curfew_warning", it) }
                if (!snap.curfewChecked) put("curfew_status", "не перевірено")
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}

/** `nav.add_stop` — adds a via waypoint to the active live navigation. */
class NavAddWaypointTool(
    private val requestAddWaypoint: (lat: Double, lon: Double, name: String?) -> Unit,
    private val http: GeoHttp? = null,
    private val requestMapOpen: () -> Unit = {},
) : Tool {

    override val id = "nav.add_stop"

    override val description =
        "Додати проміжну зупинку (АЗС, кафе, укриття, аптека або адреса) до активного маршруту навігації на ходу. " +
            "Приймає координати (lat/lon) або назву/адресу (stop/query/name/address)."

    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("stop", JsonPrimitive("string?"))
        put("query", JsonPrimitive("string?"))
        put("name", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val streaming: Boolean = false
    override val manifest = navManifest("tool.nav.add_stop", "Додати зупинку")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val rawLat = args.coord("lat")
        val rawLon = args.coord("lon")
        val stopQuery = args["stop"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: args["query"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: args["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val resolved = resolveSmartCoordinate(rawLat, rawLon, stopQuery, http)
        if (resolved == null) {
            return ToolResult.Err(
                "bad_args",
                "Проміжну зупинку не додав: " + smartCoordinateRefusal(stopQuery),
                dur(),
            )
        }
        val (lat, lon) = resolved
        requestAddWaypoint(lat, lon, stopQuery)
        requestMapOpen()

        val label = stopQuery ?: "зупинка (${String.format(java.util.Locale.US, "%.4f, %.4f", lat, lon)})"
        return ToolResult.Ok(
            output = "Додав зупинку «$label» до поточного маршруту. Навігатор перераховує коридор руху.",
            structured = buildJsonObject {
                put("lat", lat)
                put("lon", lon)
                if (stopQuery != null) put("name", stopQuery)
                put("state", "waypoint_added")
            },
            durationMs = dur(),
        )
    }
}

/**
 * NAV-Ω1 — `nav.deadline`: «маю бути о 18:00». Hands the parsed moment to
 * the map surface (same nav-command flow as nav.start); the map shows the
 * leave-by preview and, once guiding, speaks slack-band transitions.
 */
class NavDeadlineTool(
    private val requestDeadline: (epochMs: Long?) -> Unit,
    private val requestMapOpen: () -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Tool {

    override val id = "nav.deadline"

    override val description =
        "Встановити дедлайн прибуття для активного/майбутнього маршруту («маю бути о 18:00», «встигни до 9:30»). " +
            "time — час HH:MM у місцевому часі (якщо він сьогодні вже минув — інструмент бере завтра і каже про це). " +
            "Мапа покаже «виїзд до …» на банері маршруту і попереджатиме голосом, коли запас тане чи повертається. " +
            "clear=true — зняти відлік. Оцінка часу — з OSRM; це прогноз, не гарантія."

    override val argsSchema: JsonObject = buildJsonObject {
        put("time", JsonPrimitive("string?"))
        put("clear", JsonPrimitive("boolean?"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = navManifest("tool.nav.deadline", "Відлік прибуття")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val clear = args["clear"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true
        if (clear) {
            requestDeadline(null)
            return ToolResult.Ok(
                output = "Зняв відлік прибуття — часу більше не рахую.",
                structured = buildJsonObject {
                    put("cleared", true)
                    put("state", "handed_to_map")
                },
                durationMs = dur(),
            )
        }
        val raw = args["time"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "Не назвав часу прибуття. Скажи його як 18:00 або 8:30 — " +
                    "або передай clear=true, щоб зняти наявний відлік.",
                dur(),
            )
        val now = nowMs()
        val epochMs = parseHhMm(raw, now)
            ?: return ToolResult.Err(
                "bad_args",
                "Не розібрав час «$raw». Назви його годинами й хвилинами: 18:00, 8:30. " +
                    "Дати цей відлік не приймає — на іншу добу став нагадування (reminder.add).",
                dur(),
            )

        requestDeadline(epochMs)
        requestMapOpen()
        val uk = java.util.Locale("uk", "UA")
        val hhmm = java.text.SimpleDateFormat("HH:mm", uk).format(java.util.Date(epochMs))
        val tomorrow = !sameDay(now, epochMs)
        val minutesLeft = ((epochMs - now) / 60_000L).toInt()
        return ToolResult.Ok(
            output = buildString {
                append("Відлік: прибути до $hhmm")
                append(if (tomorrow) " ЗАВТРА (сьогодні цей час уже минув)" else " сьогодні")
                append(" — це ${leftWords(minutesLeft)} від зараз. Мапа покаже, коли треба виїхати, ")
                append("і попередить голосом, якщо за оцінкою перестаємо встигати. ")
                append("Час — оцінка, не гарантія.")
            },
            structured = buildJsonObject {
                put("deadline_epoch_ms", epochMs)
                put("deadline", hhmm)
                put("deadline_day", if (tomorrow) "завтра" else "сьогодні")
                put("minutes_left", minutesLeft)
                put("state", "handed_to_map")
                put("source", "годинник пристрою; запас рахується за оцінкою OSRM")
                put("certainty", "прогноз, не гарантія")
            },
            durationMs = dur(),
        )
    }

    private companion object {
        fun leftWords(minutes: Int): String =
            if (minutes >= 60) "${minutes / 60} год ${minutes % 60} хв" else "$minutes хв"

        /** "18:00" / "1800" / "18.00" today (tomorrow if past) → epoch ms. */
        fun parseHhMm(raw: String, nowMs: Long): Long? {
            val m = Regex("""^(\d{1,2})[:. ]?(\d{2})$""").find(raw.trim()) ?: return null
            val h = m.groupValues[1].toInt().takeIf { it in 0..23 } ?: return null
            val min = m.groupValues[2].toInt().takeIf { it in 0..59 } ?: return null
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(java.util.Calendar.HOUR_OF_DAY, h)
                set(java.util.Calendar.MINUTE, min)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= nowMs) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }

        fun sameDay(a: Long, b: Long): Boolean {
            val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
            val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
            return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
                ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
        }
    }
}

/** `nav.stop` — end the live guidance session and clear the route. */
class NavStopTool(
    private val requestNavStop: () -> Unit,
) : Tool {

    override val id = "nav.stop"

    override val description =
        "Зупинити живу покрокову навігацію (запущену nav.start або кнопкою «старт») і прибрати маршрут з мапи."

    override val argsSchema: JsonObject = buildJsonObject {}

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val streaming: Boolean = false
    override val manifest = navManifest("tool.nav.stop", "Стоп навігація")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        requestNavStop()
        return ToolResult.Ok(
            output = "Дав мапі команду зупинити навігацію і прибрати маршрут. " +
                "Якщо маршруту не було — нічого й не змінилось.",
            structured = buildJsonObject {
                put("stopped", true)
                put("state", "handed_to_map")
                put("route_was_active", "невідомо")
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}
