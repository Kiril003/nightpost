package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.core.data.reminders.ReminderEntity
import local.phantom.companion.core.data.reminders.ReminderStore
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * Time-based reminders as verbs: `reminder.add` (absolute `at_ms` or relative
 * `in_minutes`), `reminder.list` (pending), `reminder.remove` (by id or by what
 * the reminder says). The app's own scheduler surfaces the due rows; the tools
 * only own the store.
 *
 * Every answer repeats the resolved moment in words. The model is the part
 * of this chain that gets time wrong, and a reminder set for the wrong hour
 * is indistinguishable from one that was never set — unless the operator
 * hears the hour back.
 */

private fun ReminderEntity.toJson(nowMs: Long) = buildJsonObject {
    put("id", id)
    put("text", text)
    put("trigger_at_ms", triggerAtMs)
    put("trigger_at", whenWords(triggerAtMs, nowMs))
    recurrence?.let { put("recurrence", it) }
}

/** «сьогодні о 18:00» / «завтра о 09:00» / «27 липня о 09:00». */
private fun whenWords(epochMs: Long, nowMs: Long): String {
    val uk = java.util.Locale("uk", "UA")
    val time = java.text.SimpleDateFormat("HH:mm", uk).format(java.util.Date(epochMs))
    val day = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
    val sameYear = day.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
    val dayDelta = if (sameYear) {
        day.get(java.util.Calendar.DAY_OF_YEAR) - now.get(java.util.Calendar.DAY_OF_YEAR)
    } else {
        Int.MAX_VALUE
    }
    return when (dayDelta) {
        0 -> "сьогодні о $time"
        1 -> "завтра о $time"
        else -> java.text.SimpleDateFormat("d MMMM", uk).format(java.util.Date(epochMs)) + " о $time"
    }
}

/** `reminder.add` — set a reminder for an absolute time or `in_minutes` from now. */
class ReminderAddTool(
    private val store: ReminderStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Tool {
    override val id = "reminder.add"
    override val description =
        "Поставити нагадування. text — про що; local_time — час СЛОВАМИ оператора у МІСЦЕВОМУ часі, \"18:00\" або " +
            "\"2026-07-27 09:00\" (пристрій сам переведе — надавай перевагу ЦЬОМУ полю, epoch не рахуй); " +
            "in_minutes — через скільки хвилин від зараз; at_ms — epoch ms, лише якщо він точно відомий; " +
            "recurrence — RRULE для повторів (опц.). Час у минулому не приймається. " +
            "Повертає розібраний момент словами — переказуй його оператору дослівно."
    override val argsSchema: JsonObject = buildJsonObject {
        put("text", JsonPrimitive("string"))
        put("local_time", JsonPrimitive("string?"))
        put("in_minutes", JsonPrimitive("number?"))
        put("at_ms", JsonPrimitive("number?"))
        put("recurrence", JsonPrimitive("string?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.reminder.add", "Нагадування")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val now = nowMs()
        val text = args["text"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "Нагадування без тексту нічого оператору не скаже. Передай text — його ж словами, про що нагадати.",
                dur(),
            )
        val atMs = args["at_ms"]?.jsonPrimitive?.longOrNull
        val inMinutes = args["in_minutes"]?.jsonPrimitive?.intOrNull
        val rawLocal = args["local_time"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        // local_time first: it is the only one the model gets right.
        val local = rawLocal?.let { resolveLocalTime(it, now) }
        if (rawLocal != null && local == null) {
            return ToolResult.Err(
                "bad_args",
                "Час «$rawLocal» не розібрав. Пиши як 18:00 (найближчі такі години) або 2026-08-16 09:00. " +
                    "Якщо це «через годину» — передай in_minutes=60.",
                dur(),
            )
        }
        val source = when {
            local != null -> "local_time «$rawLocal», переведено годинником пристрою"
            inMinutes != null -> "in_minutes=$inMinutes від зараз"
            else -> "at_ms від моделі"
        }
        val triggerAtMs = local
            ?: inMinutes?.let { now + it.coerceAtLeast(0) * 60_000L }
            ?: atMs
            ?: return ToolResult.Err(
                "bad_args",
                "Не знаю, коли нагадати. Передай local_time («18:00»), in_minutes (через скільки хвилин) або at_ms.",
                dur(),
            )
        if (triggerAtMs <= now) {
            return ToolResult.Err(
                "time_in_past",
                "Цей момент (${whenWords(triggerAtMs, now)}) уже минув — таке нагадування не спрацює ніколи. " +
                    "Назви час у майбутньому або передай in_minutes.",
                dur(),
            )
        }
        val recurrence = args["recurrence"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val id = runCatching {
            store.add(text = text, triggerAtMs = triggerAtMs, recurrence = recurrence)
        }.getOrElse { e ->
            return ToolResult.Err(
                "store_error",
                "Не вдалось зберегти нагадування (${e.message ?: "сховище відмовило"}). " +
                    "Спробуй ще раз; на крайній випадок постав системний будильник (phone.alarm).",
                dur(),
            )
        }
        val minutesLeft = ((triggerAtMs - now) / 60_000L).toInt()
        return ToolResult.Ok(
            output = "Нагадаю «$text» ${whenWords(triggerAtMs, now)}" +
                (recurrence?.let { ", далі за правилом повтору" } ?: "") +
                ". Це через $minutesLeft хв.",
            structured = buildJsonObject {
                put("id", id)
                put("text", text)
                put("trigger_at_ms", triggerAtMs)
                put("trigger_at", whenWords(triggerAtMs, now))
                put("minutes_left", minutesLeft)
                recurrence?.let { put("recurrence", it) }
                put("source", source)
            },
            durationMs = dur(),
        )
    }
}

/** `reminder.list` — pending (not-yet-fired) reminders. */
class ReminderListTool(
    private val store: ReminderStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Tool {
    override val id = "reminder.list"
    override val description =
        "Показати активні (ще не спрацьовані) нагадування, найближчі першими. limit — скільки (типово 20). " +
            "Кожен рядок несе момент словами — не рахуй час сам."
    override val argsSchema: JsonObject = buildJsonObject { put("limit", JsonPrimitive("number?")) }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.reminder.list", "Список нагадувань")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val now = nowMs()
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 100)
        val reminders = runCatching { store.pending(limit) }
            .getOrElse { e ->
                return ToolResult.Err(
                    "store_error",
                    "Не вдалось прочитати нагадування (${e.message ?: "сховище відмовило"}). " +
                        "Спробуй ще раз або подивись їх на екрані нагадувань.",
                    dur(),
                )
            }
        if (reminders.isEmpty()) {
            return ToolResult.Ok(
                output = "Активних нагадувань нема. Поставити — reminder.add: текст і час.",
                structured = buildJsonObject {
                    put("reminders", buildJsonArray { })
                    put("shown", 0)
                },
                durationMs = dur(),
            )
        }
        return ToolResult.Ok(
            output = reminders.joinToString("\n") { "• ${it.text} — ${whenWords(it.triggerAtMs, now)}" },
            structured = buildJsonObject {
                put("reminders", buildJsonArray { reminders.forEach { add(it.toJson(now)) } })
                put("shown", reminders.size)
                if (reminders.size == limit) put("truncated", "показано перші $limit — можуть бути ще")
            },
            durationMs = dur(),
        )
    }
}

/** `reminder.remove` — drop a reminder by id, or by the words on it. */
class ReminderRemoveTool(
    private val store: ReminderStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Tool {
    override val id = "reminder.remove"
    override val description =
        "Прибрати нагадування. text — частина тексту нагадування («полити квіти»), НАДІЙНІШИЙ спосіб: " +
            "id оператор не бачить. id — точний ідентифікатор з reminder.list/reminder.add, якщо він у тебе є. " +
            "Якщо під text підходить кілька — інструмент поверне їх список, перепитай оператора."
    override val argsSchema: JsonObject = buildJsonObject {
        put("text", JsonPrimitive("string?"))
        put("id", JsonPrimitive("string?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val askEveryCall: Boolean = true
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.reminder.remove", "Прибрати нагадування")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val start = System.currentTimeMillis()
        val id = args["id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val text = args["text"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        fun dur() = System.currentTimeMillis() - start
        if (id == null && text == null) {
            return ToolResult.Err(
                "bad_args",
                "Не сказав, яке нагадування прибрати. Передай text — словами з нагадування; " +
                    "спершу можеш подивитись reminder.list.",
                dur(),
            )
        }
        val now = nowMs()
        fun removed(row: ReminderEntity) = ToolResult.Ok(
            output = "Прибрав нагадування «${row.text}» (мало спрацювати ${whenWords(row.triggerAtMs, now)}).",
            structured = buildJsonObject {
                put("removed", 1)
                put("id", row.id)
                put("text", row.text)
                put("trigger_at", whenWords(row.triggerAtMs, now))
            },
            durationMs = dur(),
        )
        return runCatching {
            if (id != null) {
                val row = store.get(id)
                if (row != null && store.remove(id)) return@runCatching removed(row)
            }
            // A model that has no id often puts the words in `id` — treat them as words.
            val needle = text ?: id!!
            val matches = matchByText(store.pending(MAX_SCAN), needle) { it.text }
            when (matches.size) {
                0 -> ToolResult.Err(
                    "not_found",
                    "Нагадування «$needle» серед активних немає. Подивись reminder.list і назви його словами.",
                    dur(),
                )
                1 -> {
                    store.remove(matches[0].id)
                    removed(matches[0])
                }
                else -> ToolResult.Err(
                    "ambiguous",
                    "Підходить кілька: " + matches.joinToString("; ") { "«${it.text}»" } +
                        ". Спитай оператора, яке саме прибрати — вгадувати не буду.",
                    dur(),
                )
            }
        }.getOrElse { e ->
            ToolResult.Err(
                "store_error",
                "Не вдалось прибрати нагадування (${e.message ?: "сховище відмовило"}). " +
                    "Спробуй ще раз або зроби це на екрані нагадувань.",
                dur(),
            )
        }
    }

    private companion object {
        const val MAX_SCAN = 100
    }
}
