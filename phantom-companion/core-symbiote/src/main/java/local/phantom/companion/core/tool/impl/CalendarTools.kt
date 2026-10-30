package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.data.calendar.CalendarEventEntity
import local.phantom.companion.core.data.calendar.CalendarStore
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * The advanced local event calendar as verbs the model can call: `calendar.add`
 * writes an event, `calendar.list` reads the window ahead, `calendar.remove`
 * drops one. All offline, all on the SQLCipher DB — no external calendar.
 *
 * Every answer here carries the WALL CLOCK, not only the epoch: a confirmation
 * the operator cannot check («Додав подію (id=8f2c…)») is not a confirmation.
 * The epoch keys stay exactly as the agenda card reads them — `start_ms`,
 * `end_ms`, `all_day`, `category` — and the human phrase rides alongside them.
 */

internal fun dataSystemManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 0,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    abusableAgainstWeak = false,
    sunsetAfterDaysIdle = 365,
    surfaces = setOf(FeatureManifest.Surface.STREAM),
)

/** SimpleDateFormat holds mutable state and tools run concurrently — never cache one. */
private fun format(pattern: String, ms: Long): String =
    java.text.SimpleDateFormat(pattern, java.util.Locale("uk", "UA")).format(java.util.Date(ms))

private fun dayGap(fromMs: Long, toMs: Long): Long {
    val cal = java.util.Calendar.getInstance()
    fun dayStart(ms: Long): Long {
        cal.timeInMillis = ms
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
    // Rounded because a DST day is 23 or 25 hours long, not 24.
    return Math.round((dayStart(toMs) - dayStart(fromMs)) / 86_400_000.0).toLong()
}

/**
 * «завтра 27.07 09:00–10:30» — the day named in words where naming it is
 * possible, because «18:00» said at 19:00 quietly means tomorrow and the
 * operator has no way to see that in an epoch.
 */
private fun whenPhrase(nowMs: Long, startMs: Long, endMs: Long?, allDay: Boolean): String {
    val body = if (allDay) {
        "${format("dd.MM", startMs)}, весь день"
    } else {
        format("dd.MM HH:mm", startMs) + (endMs?.let { "–" + format("HH:mm", it) } ?: "")
    }
    return when (dayGap(nowMs, startMs)) {
        0L -> "сьогодні $body"
        1L -> "завтра $body"
        else -> body
    }
}

private fun CalendarEventEntity.localWhen(nowMs: Long): String =
    whenPhrase(nowMs, startMs, endMs, allDay)

/** Field for field what AgendaEvent decodes, plus the phrase the model repeats. */
private fun CalendarEventEntity.toJson(nowMs: Long) = buildJsonObject {
    put("id", id)
    put("title", title)
    put("start_ms", startMs)
    endMs?.let { put("end_ms", it) }
    put("all_day", allDay)
    put("category", category)
    location?.let { put("location", it) }
    lat?.let { put("lat", it) }
    lon?.let { put("lon", it) }
    notes?.let { put("notes", it) }
    put("local_when", localWhen(nowMs))
}

/** `calendar.add` — write a new event into the local calendar. */
class CalendarAddTool(private val store: CalendarStore) : Tool {
    override val id = "calendar.add"
    override val description =
        "Додати подію в локальний календар. title — назва; local_time — початок СЛОВАМИ оператора у МІСЦЕВОМУ часі, " +
            "\"18:00\" або \"2026-07-27 09:00\" (пристрій сам переведе — надавай перевагу ЦЬОМУ полю, epoch не рахуй); " +
            "local_end — кінець так само словами (опц.); start_ms/end_ms — epoch ms, лише якщо він точно відомий; " +
            "all_day — на весь день; category — meeting/birthday/trip/…; " +
            "location/lat/lon — місце; notes — деталі. Повертає local_when — повтори оператору саме його."
    override val argsSchema: JsonObject = buildJsonObject {
        put("title", JsonPrimitive("string"))
        put("local_time", JsonPrimitive("string?"))
        put("local_end", JsonPrimitive("string?"))
        put("start_ms", JsonPrimitive("number?"))
        put("end_ms", JsonPrimitive("number?"))
        put("all_day", JsonPrimitive("boolean?"))
        put("category", JsonPrimitive("string?"))
        put("location", JsonPrimitive("string?"))
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("notes", JsonPrimitive("string?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.calendar.add", "Додати подію")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val now = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - now
        val title = args["title"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "потрібна назва: передай title, напр. title=\"Стоматолог\".",
                0L,
            )

        // local_time first: it is the only one the model gets right.
        val rawStart = args["local_time"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val wordedStart = rawStart?.let { resolveLocalTime(it, now) }
        if (rawStart != null && wordedStart == null) {
            return ToolResult.Err(
                "bad_time",
                "не розібрав час «$rawStart», подію НЕ записав. Дай local_time рівно як \"18:00\" " +
                    "або \"2026-07-27 09:00\"; слова («завтра ввечері») спершу переклади в цей формат.",
                dur(),
            )
        }
        val startMs = wordedStart
            ?: args["start_ms"]?.jsonPrimitive?.longOrNull
            ?: return ToolResult.Err(
                "bad_args",
                "потрібен local_time (\"18:00\") або start_ms (epoch ms) — без часу подію не записую. " +
                    "Якщо оператор часу не назвав, спитай його.",
                0L,
            )

        // «11:00» for an event that starts tomorrow means tomorrow 11:00, so the end resolves against the start.
        val rawEnd = args["local_end"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val wordedEnd = rawEnd?.let { resolveLocalTime(it, startMs) }
        if (rawEnd != null && wordedEnd == null) {
            return ToolResult.Err(
                "bad_time",
                "не розібрав кінець «$rawEnd», подію НЕ записав. Дай local_end як \"10:30\" " +
                    "або \"2026-07-27 10:30\", чи не давай його зовсім.",
                dur(),
            )
        }
        val endMs = wordedEnd ?: args["end_ms"]?.jsonPrimitive?.longOrNull
        if (endMs != null && endMs < startMs) {
            return ToolResult.Err(
                "bad_range",
                "кінець (${format("dd.MM HH:mm", endMs)}) раніший за початок " +
                    "(${format("dd.MM HH:mm", startMs)}) — подію НЕ записав. Перевір дату кінця.",
                dur(),
            )
        }

        val allDay = args["all_day"]?.jsonPrimitive?.booleanOrNull ?: false
        val category = args["category"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() } ?: "event"
        val location = args["location"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val id = runCatching {
            store.add(
                title = title,
                startMs = startMs,
                endMs = endMs,
                allDay = allDay,
                category = category,
                location = location,
                lat = args["lat"]?.jsonPrimitive?.doubleOrNull,
                lon = args["lon"]?.jsonPrimitive?.doubleOrNull,
                notes = args["notes"]?.jsonPrimitive?.content,
            )
        }.getOrElse { e ->
            return ToolResult.Err(
                "store_error",
                "календар не прийняв запис (${e.message ?: "збій сховища"}). Події в календарі НЕМА — " +
                    "скажи це оператору прямо і спробуй ще раз.",
                dur(),
            )
        }
        if (id.isEmpty()) {
            return ToolResult.Err(
                "store_error",
                "сховище не повернуло id — події в календарі НЕМА. Не кажи, що додав.",
                dur(),
            )
        }

        val phrase = whenPhrase(now, startMs, endMs, allDay)
        val where = location?.let { ", $it" }.orEmpty()
        return ToolResult.Ok(
            output = "Додав «$title» — $phrase$where.",
            structured = buildJsonObject {
                put("id", id)
                put("title", title)
                put("start_ms", startMs)
                endMs?.let { put("end_ms", it) }
                put("all_day", allDay)
                put("category", category)
                location?.let { put("location", it) }
                put("local_when", phrase)
                put("time_source", if (wordedStart != null) "local_time" else "start_ms")
            },
            durationMs = dur(),
        )
    }
}

/** `calendar.list` — events from now (or `from_ms`) forward. */
class CalendarListTool(private val store: CalendarStore) : Tool {
    override val id = "calendar.list"
    override val description =
        "Показати найближчі події з календаря — з часом кожної. from_ms — від якого часу (epoch ms, типово зараз); " +
            "days — на скільки днів уперед дивитись (опц.: 1 = решта доби, 7 = тиждень); limit — скільки (типово 20). " +
            "Відповідь каже, чи список обрізано (truncated) — не видавай обрізаний список за повний."
    override val argsSchema: JsonObject = buildJsonObject {
        put("from_ms", JsonPrimitive("number?"))
        put("days", JsonPrimitive("number?"))
        put("limit", JsonPrimitive("number?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.calendar.list", "Список подій")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val now = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - now
        val fromArg = args["from_ms"]?.jsonPrimitive?.longOrNull
        val from = fromArg ?: now
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 100)
        val days = args["days"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.coerceAtMost(366)
        val until = days?.let { from + it * 86_400_000L }

        // One row past the limit is what tells truncation from a complete answer.
        val fetchLimit = if (until == null) (limit + 1).coerceAtMost(101) else WINDOW_FETCH
        val fetched = runCatching { store.upcoming(from, fetchLimit) }
            .getOrElse { e ->
                return ToolResult.Err(
                    "store_error",
                    "не прочитав календар (${e.message ?: "збій сховища"}). НЕ кажи оператору, що подій нема — " +
                        "цього ми не знаємо; спробуй ще раз.",
                    dur(),
                )
            }
        val inWindow = if (until == null) fetched else fetched.filter { it.startMs <= until }
        val events = inWindow.take(limit)
        val truncated = inWindow.size > events.size

        val window = buildString {
            append(if (fromArg == null) "від зараз" else "від " + format("dd.MM HH:mm", from))
            if (days != null) append(", на $days дн. уперед")
        }

        val text = if (events.isEmpty()) {
            "Подій нема ($window)."
        } else {
            val head = "Подій: ${events.size}${if (truncated) "+ (показано перші $limit)" else ""} · $window."
            val rows = events.joinToString("\n") { e ->
                val tail = buildString {
                    if (e.category != "event") append(" · ${e.category}")
                    e.location?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
                "• ${e.localWhen(now)} · ${e.title}$tail"
            }
            "$head\n$rows"
        }

        return ToolResult.Ok(
            output = text,
            structured = buildJsonObject {
                put("events", buildJsonArray { events.forEach { add(it.toJson(now)) } })
                put("from_ms", from)
                put("returned", events.size)
                put("limit", limit)
                put("truncated", truncated)
                days?.let { put("days", it) }
            },
            durationMs = dur(),
        )
    }

    private companion object {
        /** A windowed read filters after the fetch, so it must fetch wide enough to fill the window. */
        const val WINDOW_FETCH = 200
    }
}

/** `calendar.remove` — delete an event by id. */
class CalendarRemoveTool(private val store: CalendarStore) : Tool {
    override val id = "calendar.remove"
    override val description =
        "Прибрати подію з календаря. title — частина назви події («стоматолог»), НАДІЙНІШИЙ спосіб: id оператор не бачить. " +
            "id — точний ідентифікатор з calendar.list/calendar.add, якщо він у тебе є. " +
            "Шукає лише серед МАЙБУТНІХ подій. Якщо під title підходить кілька — інструмент поверне їх з часом, перепитай оператора."
    override val argsSchema: JsonObject = buildJsonObject {
        put("title", JsonPrimitive("string?"))
        put("id", JsonPrimitive("string?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val askEveryCall: Boolean = true
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.calendar.remove", "Прибрати подію")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val now = System.currentTimeMillis()
        val id = args["id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val title = args["title"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        if (id == null && title == null) {
            return ToolResult.Err(
                "bad_args",
                "потрібен title (частина назви, як на картці) або id — інакше не знаю, що прибирати.",
                0L,
            )
        }
        fun dur() = System.currentTimeMillis() - now
        fun removed(row: CalendarEventEntity): ToolResult = ToolResult.Ok(
            output = "Прибрав «${row.title}» — ${row.localWhen(now)}.",
            structured = buildJsonObject {
                put("removed", 1)
                put("id", row.id)
                put("title", row.title)
                put("start_ms", row.startMs)
                put("local_when", row.localWhen(now))
            },
            durationMs = dur(),
        )
        return runCatching {
            val byId = id?.let { store.get(it) }
            if (byId != null) {
                if (!store.remove(byId.id)) {
                    return@runCatching ToolResult.Err(
                        "store_error",
                        "не зміг прибрати «${byId.title}» — подія ЛИШИЛАСЬ у календарі. Скажи це оператору.",
                        dur(),
                    )
                }
                return@runCatching removed(byId)
            }
            val needle = title ?: id!!
            val pool = store.upcoming(now, 100)
            val matches = matchByText(pool, needle) { it.title }
            when (matches.size) {
                0 -> ToolResult.Err(
                    "not_found",
                    "«$needle» немає серед ${pool.size} майбутніх подій (минулі цей інструмент не бачить). " +
                        "Виклич calendar.list і звір назву, або спитай оператора.",
                    dur(),
                )
                1 -> {
                    val hit = matches[0]
                    if (store.remove(hit.id)) {
                        removed(hit)
                    } else {
                        ToolResult.Err(
                            "store_error",
                            "не зміг прибрати «${hit.title}» — подія ЛИШИЛАСЬ у календарі. Скажи це оператору.",
                            dur(),
                        )
                    }
                }
                else -> ToolResult.Err(
                    "ambiguous",
                    "Підходить ${matches.size}: " +
                        matches.joinToString("; ") { "«${it.title}» ${it.localWhen(now)}" } +
                        ". Нічого не прибрав — спитай оператора, яку саме, і передай точний title.",
                    dur(),
                )
            }
        }.getOrElse { e ->
            ToolResult.Err(
                "store_error",
                "календар не відповів (${e.message ?: "збій сховища"}). Подію НЕ прибрано.",
                dur(),
            )
        }
    }
}
