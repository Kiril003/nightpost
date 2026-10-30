package local.phantom.companion.core.tool.impl

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

/**
 * R5 — Device Hands: the phone-command tool family. Every tool here is a
 * thin, honest wrapper over a SYSTEM intent — PHANTOM never dials, sends,
 * or saves on its own; where the action has consequences (call, SMS,
 * calendar event) the system UI opens pre-filled and the operator confirms
 * with their own finger. Alarm/timer use `EXTRA_SKIP_UI` so «постав
 * будильник на 7» works end-to-end by voice (needs the
 * `com.android.alarm.permission.SET_ALARM` manifest permission — declared
 * in :app).
 *
 * The limit every answer here has to carry: launching an intent is not
 * proof the other app obeyed it. `EXTRA_SKIP_UI` is a request, and a clock
 * that ignores it opens its own screen instead. So these verbs answer
 * «передав» with a `delivery` field, never «зроблено».
 *
 * Argument parsing is split into pure `parse*` functions returning
 * [Parsed] so the validation matrix is unit-testable on the JVM without
 * Robolectric; only the final Intent launch touches the framework.
 *
 * Flashlight / volume / brightness / DND intentionally stay in the older
 * `settings` tool (already real) — R5 adds only the intent-based hands.
 */

internal sealed interface Parsed<out T> {
    data class Ok<T>(val value: T) : Parsed<T>
    data class Bad(val message: String) : Parsed<Nothing>
}

private fun bad(message: String) = Parsed.Bad(message)

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.num(key: String): Double? =
    this[key]?.jsonPrimitive?.doubleOrNull

private fun phoneManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 1,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    abusableAgainstWeak = false,
    sunsetAfterDaysIdle = 180,
    surfaces = setOf(FeatureManifest.Surface.STREAM),
)

/** Що саме сталось із чужим застосунком — і що робити далі. */
private fun handoffFailure(
    e: Throwable,
    missingCode: String,
    missingMessage: String,
    elapsedMs: Long,
): ToolResult.Err = when (e) {
    is android.content.ActivityNotFoundException -> ToolResult.Err(missingCode, missingMessage, elapsedMs)
    is SecurityException -> ToolResult.Err(
        "no_permission",
        "Android не пустив: ${e.message ?: "без деталей"}. Дозволи Nightpost цю дію в налаштуваннях застосунку і повтори.",
        elapsedMs,
    )
    else -> ToolResult.Err(
        "handoff_failed",
        "Не вдалось передати дію системі: ${e.message ?: "система відмовила без пояснення"}. Зроби це вручну.",
        elapsedMs,
    )
}

private const val HANDOFF_NOTE =
    "передано системному застосунку; Nightpost не бачить, чи він прийняв — перевір у ньому"

// ---------------------------------------------------------------------------
// phone.alarm
// ---------------------------------------------------------------------------

internal data class AlarmSpec(
    val hour: Int,
    val minute: Int,
    val message: String?,
    /** [Calendar] day constants (SUNDAY=1 … SATURDAY=7); empty = one-shot. */
    val days: List<Int>,
)

internal fun parseAlarmArgs(args: JsonObject): Parsed<AlarmSpec> {
    val hour = args.num("hour")?.toInt()
        ?: return bad("Не назвав години. Передай hour — ціле число 0–23 у місцевому часі.")
    if (hour !in 0..23) return bad("Години $hour не існує: hour має бути 0–23. Північ — це 0, полудень — 12.")
    val minute = args.num("minute")?.toInt() ?: 0
    if (minute !in 0..59) return bad("Хвилини $minute не існує: minute має бути 0–59.")
    val dayTokens = args["days"]?.let { el ->
        runCatching { el.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
    }.orEmpty()
    val days = dayTokens.map { tok ->
        when (tok.lowercase().take(3)) {
            "mon", "пон", "пн" -> Calendar.MONDAY
            "tue", "вів", "вт" -> Calendar.TUESDAY
            "wed", "сер", "ср" -> Calendar.WEDNESDAY
            "thu", "чет", "чт" -> Calendar.THURSDAY
            "fri", "пят", "пт", "пʼя" -> Calendar.FRIDAY
            "sat", "суб", "сб" -> Calendar.SATURDAY
            "sun", "нед", "нд" -> Calendar.SUNDAY
            else -> return bad(
                "Дня «$tok» не розумію. Дні тижня в days пиши як mon, tue, wed, thu, fri, sat, sun " +
                    "(або пн, вт, ср, чт, пт, сб, нд).",
            )
        }
    }
    return Parsed.Ok(AlarmSpec(hour, minute, args.str("message"), days))
}

private val DAY_WORDS = mapOf(
    Calendar.MONDAY to "пн", Calendar.TUESDAY to "вт", Calendar.WEDNESDAY to "ср",
    Calendar.THURSDAY to "чт", Calendar.FRIDAY to "пт", Calendar.SATURDAY to "сб",
    Calendar.SUNDAY to "нд",
)

/** `phone.alarm` — set a system alarm (also the WHEN half of /remind). */
class PhoneAlarmTool(
    private val launch: (Intent) -> Unit,
) : Tool {

    override val id = "phone.alarm"

    override val description =
        "Поставити системний будильник/нагадування на конкретний час: hour (0-23), minute?, " +
            "message? (текст нагадування — його покаже дзвінок), days? (масив mon…sun для повторюваного). " +
            "Проситься без відкриття годинника; якщо годинник цього не вміє, він відкриється з уже " +
            "заповненим часом — тому кажи «передав», а не «поставив»."

    override val argsSchema: JsonObject = buildJsonObject {
        put("hour", JsonPrimitive("number"))
        put("minute", JsonPrimitive("number?"))
        put("message", JsonPrimitive("string?"))
        put("days", JsonPrimitive("array?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LAUNCHER)
    override val risk: ToolRisk = ToolRisk.DEVICE
    override val streaming = false
    override val manifest = phoneManifest("tool.phone.alarm", "Будильник")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        if (ToolPermission.LAUNCHER !in context.approvedPermissions) {
            return ToolResult.PermissionDenied(ToolPermission.LAUNCHER, dur())
        }
        val spec = when (val p = parseAlarmArgs(args)) {
            is Parsed.Bad -> return ToolResult.Err("bad_args", p.message, dur())
            is Parsed.Ok -> p.value
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, spec.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, spec.minute)
            spec.message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            if (spec.days.isNotEmpty()) {
                putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(spec.days))
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val hhmm = "%02d:%02d".format(spec.hour, spec.minute)
        val dayWords = spec.days.mapNotNull { DAY_WORDS[it] }
        return try {
            launch(intent)
            ToolResult.Ok(
                output = buildString {
                    append("Передав годиннику будильник на $hhmm")
                    if (dayWords.isNotEmpty()) append(" щотижня (${dayWords.joinToString(", ")})")
                    spec.message?.let { append(" — «$it»") }
                    append(". Перевір у годиннику, що він зʼявився.")
                },
                structured = buildJsonObject {
                    put("at", hhmm)
                    put("hour", spec.hour)
                    put("minute", spec.minute)
                    spec.message?.let { put("message", it) }
                    put("repeats", dayWords.isNotEmpty())
                    if (dayWords.isNotEmpty()) {
                        put("days", buildJsonArray { dayWords.forEach { add(JsonPrimitive(it)) } })
                    }
                    put("delivery", HANDOFF_NOTE)
                },
                durationMs = dur(),
            )
        } catch (e: Exception) {
            handoffFailure(
                e,
                "no_clock_app",
                "На пристрої немає застосунку годинника, тож будильник ставити нікуди. " +
                    "Постав нагадування в Nightpost (reminder.add) — воно спрацює без годинника.",
                dur(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// phone.timer
// ---------------------------------------------------------------------------

internal data class TimerSpec(val lengthSec: Int, val message: String?)

internal fun parseTimerArgs(args: JsonObject): Parsed<TimerSpec> {
    val minutes = args.num("minutes")
    val seconds = args.num("seconds")
    if (minutes == null && seconds == null) {
        return bad("Не назвав, на скільки. Передай minutes і/або seconds — скільки відлічувати від зараз.")
    }
    val total = ((minutes ?: 0.0) * 60 + (seconds ?: 0.0)).toInt()
    if (total < 1) return bad("Відлік має бути щонайменше 1 секунда — переданий вийшов $total с.")
    if (total > 24 * 3600) return bad("Відлік довший за добу системний таймер не тримає. На довше став будильник (phone.alarm).")
    return Parsed.Ok(TimerSpec(total, args.str("message")))
}

/** `phone.timer` — set a system countdown timer («нагадай через 20 хв»). */
class PhoneTimerTool(
    private val launch: (Intent) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Tool {

    override val id = "phone.timer"

    override val description =
        "Поставити системний таймер-відлік: minutes і/або seconds, message? (підпис — його покаже дзвінок). " +
            "Для відносного часу («через 20 хвилин»). Проситься без відкриття годинника; якщо годинник цього " +
            "не вміє, він відкриється заповненим — тому кажи «передав», а не «поставив»."

    override val argsSchema: JsonObject = buildJsonObject {
        put("minutes", JsonPrimitive("number?"))
        put("seconds", JsonPrimitive("number?"))
        put("message", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LAUNCHER)
    override val risk: ToolRisk = ToolRisk.DEVICE
    override val streaming = false
    override val manifest = phoneManifest("tool.phone.timer", "Таймер")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        if (ToolPermission.LAUNCHER !in context.approvedPermissions) {
            return ToolResult.PermissionDenied(ToolPermission.LAUNCHER, dur())
        }
        val spec = when (val p = parseTimerArgs(args)) {
            is Parsed.Bad -> return ToolResult.Err("bad_args", p.message, dur())
            is Parsed.Ok -> p.value
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, spec.lengthSec)
            spec.message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pretty = when {
            spec.lengthSec % 3600 == 0 -> "${spec.lengthSec / 3600} год"
            spec.lengthSec >= 60 -> "${spec.lengthSec / 60} хв" +
                (spec.lengthSec % 60).takeIf { it > 0 }?.let { " $it с" }.orEmpty()
            else -> "${spec.lengthSec} с"
        }
        val endsAt = nowMs() + spec.lengthSec * 1000L
        return try {
            launch(intent)
            ToolResult.Ok(
                output = buildString {
                    append("Передав годиннику таймер на $pretty")
                    spec.message?.let { append(" — «$it»") }
                    append(" (спрацює о ${hhmmOf(endsAt)}). Перевір у годиннику, що він пішов.")
                },
                structured = buildJsonObject {
                    put("seconds", spec.lengthSec)
                    put("length", pretty)
                    put("ends_at_ms", endsAt)
                    put("ends_at", hhmmOf(endsAt))
                    spec.message?.let { put("message", it) }
                    put("delivery", HANDOFF_NOTE)
                },
                durationMs = dur(),
            )
        } catch (e: Exception) {
            handoffFailure(
                e,
                "no_clock_app",
                "На пристрої немає застосунку годинника, тож таймер ставити нікуди. " +
                    "Постав нагадування в Nightpost (reminder.add з in_minutes) — воно спрацює без годинника.",
                dur(),
            )
        }
    }

    private fun hhmmOf(epochMs: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale("uk", "UA"))
            .format(java.util.Date(epochMs))
}

// ---------------------------------------------------------------------------
// phone.dial
// ---------------------------------------------------------------------------

internal data class DialSpec(val number: String)

internal fun parseDialArgs(args: JsonObject): Parsed<DialSpec> {
    val raw = args.str("number")
        ?: return bad("Не назвав номера. Передай number — цифрами, можна з +380.")
    val cleaned = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    if (cleaned.count { it.isDigit() } < 3) {
        return bad("«$raw» не схоже на номер телефону. Якщо це імʼя — знайди номер у контактах і передай цифри.")
    }
    return Parsed.Ok(DialSpec(cleaned))
}

/** `phone.dial` — open the dialer pre-filled; the operator presses call. */
class PhoneDialTool(
    private val launch: (Intent) -> Unit,
) : Tool {

    override val id = "phone.dial"

    override val description =
        "Відкрити дзвонилку з набраним number — виклик НЕ стартує сам, оператор натискає кнопку дзвінка власноруч."

    override val argsSchema: JsonObject = buildJsonObject {
        put("number", JsonPrimitive("string"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LAUNCHER)
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val streaming = false
    override val manifest = phoneManifest("tool.phone.dial", "Набір номера")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        if (ToolPermission.LAUNCHER !in context.approvedPermissions) {
            return ToolResult.PermissionDenied(ToolPermission.LAUNCHER, dur())
        }
        val spec = when (val p = parseDialArgs(args)) {
            is Parsed.Bad -> return ToolResult.Err("bad_args", p.message, dur())
            is Parsed.Ok -> p.value
        }
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${spec.number}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            launch(intent)
            ToolResult.Ok(
                output = "Відкрив дзвонилку з номером ${spec.number} — натисни виклик сам.",
                structured = buildJsonObject {
                    put("number", spec.number)
                    put("called", false)
                    put("delivery", "дзвонилку відкрито; виклик робить оператор")
                },
                durationMs = dur(),
            )
        } catch (e: Exception) {
            handoffFailure(
                e,
                "no_dialer",
                "На пристрої немає дзвонилки. Номер ${spec.number} — набери його на іншому апараті.",
                dur(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// phone.sms
// ---------------------------------------------------------------------------

internal data class SmsSpec(val number: String, val body: String?)

internal fun parseSmsArgs(args: JsonObject): Parsed<SmsSpec> {
    val raw = args.str("number")
        ?: return bad("Не назвав номера. Передай number — цифрами, можна з +380.")
    val cleaned = raw.filter { it.isDigit() || it == '+' }
    if (cleaned.count { it.isDigit() } < 3) {
        return bad("«$raw» не схоже на номер телефону. Якщо це людина з мережі Nightpost — пиши через people.draft.")
    }
    return Parsed.Ok(SmsSpec(cleaned, args.str("text")))
}

/** `phone.sms` — open the SMS app pre-filled; NEVER auto-sends. */
class PhoneSmsTool(
    private val launch: (Intent) -> Unit,
) : Tool {

    override val id = "phone.sms"

    override val description =
        "Підготувати SMS: відкриває месенджер SMS з number і text уже заповненими. Повідомлення НІКОЛИ " +
            "не відправляється само — оператор натискає «надіслати» власноруч. SMS іде через оператора " +
            "звʼязку відкритим текстом — для закритого листа є people.draft."

    override val argsSchema: JsonObject = buildJsonObject {
        put("number", JsonPrimitive("string"))
        put("text", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LAUNCHER)
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val streaming = false
    override val manifest = phoneManifest("tool.phone.sms", "Чернетка SMS")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        if (ToolPermission.LAUNCHER !in context.approvedPermissions) {
            return ToolResult.PermissionDenied(ToolPermission.LAUNCHER, dur())
        }
        val spec = when (val p = parseSmsArgs(args)) {
            is Parsed.Bad -> return ToolResult.Err("bad_args", p.message, dur())
            is Parsed.Ok -> p.value
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${spec.number}")).apply {
            spec.body?.let { putExtra("sms_body", it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            launch(intent)
            ToolResult.Ok(
                output = "Відкрив чернетку SMS до ${spec.number} — перевір і надішли сам. " +
                    "SMS іде відкритим текстом через оператора звʼязку.",
                structured = buildJsonObject {
                    put("number", spec.number)
                    spec.body?.let { put("text", it) }
                    put("sent", false)
                    put("channel", "SMS — відкритий текст через оператора звʼязку")
                },
                durationMs = dur(),
            )
        } catch (e: Exception) {
            handoffFailure(
                e,
                "no_sms_app",
                "На пристрої немає SMS-застосунку. Якщо ця людина є в мережі Nightpost — напиши їй через people.draft.",
                dur(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// phone.calendar
// ---------------------------------------------------------------------------

internal data class CalendarSpec(
    val title: String,
    val beginMs: Long?,
    val endMs: Long?,
    val allDay: Boolean,
    val location: String?,
    val notes: String?,
)

/**
 * `start` accepts `YYYY-MM-DDTHH:MM`, `YYYY-MM-DD HH:MM`, `YYYY-MM-DD`
 * (all-day) or bare `HH:MM` (today). Times are wall-clock in [zone].
 */
internal fun parseCalendarArgs(
    args: JsonObject,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(),
): Parsed<CalendarSpec> {
    val title = args.str("title")
        ?: return bad("Подія без назви ні про що не каже. Передай title — те, що оператор побачить у календарі.")
    val startRaw = args.str("start")
    var allDay = args["all_day"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
    var beginMs: Long? = null
    if (startRaw != null) {
        val normalized = startRaw.replace(' ', 'T')
        val dateTime = runCatching {
            LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }.getOrNull()
            ?: runCatching { // bare date → all-day event
                LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE)
                    .also { allDay = true }.atStartOfDay()
            }.getOrNull()
            ?: runCatching { // bare time → today
                LocalTime.parse(normalized).atDate(today)
            }.getOrNull()
            ?: return bad(
                "Час «$startRaw» не розпізнав. Пиши як 2026-08-16T14:30, 2026-08-16 (цілий день) або 14:30 (сьогодні).",
            )
        beginMs = dateTime.atZone(zone).toInstant().toEpochMilli()
    }
    val durationMin = args.num("duration_min")?.toLong() ?: 60L
    if (durationMin < 1) return bad("Подія коротша за хвилину не буває: duration_min має бути ≥ 1.")
    val endMs = beginMs?.plus(durationMin * 60_000)
    return Parsed.Ok(
        CalendarSpec(
            title = title,
            beginMs = beginMs,
            endMs = if (allDay) null else endMs,
            allDay = allDay,
            location = args.str("location"),
            notes = args.str("notes"),
        ),
    )
}

/** `phone.calendar` — open a pre-filled calendar event; operator saves it. */
class PhoneCalendarTool(
    private val launch: (Intent) -> Unit,
) : Tool {

    override val id = "phone.calendar"

    override val description =
        "Чернетка події в системному календарі: title, start? (YYYY-MM-DDTHH:MM / YYYY-MM-DD / HH:MM), " +
            "duration_min? (типово 60), location?, notes?, all_day?. Відкриється редактор події — оператор " +
            "перевірить і збереже сам. Повертає розібраний час словами — звір його."

    override val argsSchema: JsonObject = buildJsonObject {
        put("title", JsonPrimitive("string"))
        put("start", JsonPrimitive("string?"))
        put("duration_min", JsonPrimitive("number?"))
        put("location", JsonPrimitive("string?"))
        put("notes", JsonPrimitive("string?"))
        put("all_day", JsonPrimitive("boolean?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LAUNCHER)
    override val risk: ToolRisk = ToolRisk.DEVICE
    override val streaming = false
    override val manifest = phoneManifest("tool.phone.calendar", "Подія в календарі")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        if (ToolPermission.LAUNCHER !in context.approvedPermissions) {
            return ToolResult.PermissionDenied(ToolPermission.LAUNCHER, dur())
        }
        val spec = when (val p = parseCalendarArgs(args)) {
            is Parsed.Bad -> return ToolResult.Err("bad_args", p.message, dur())
            is Parsed.Ok -> p.value
        }
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, spec.title)
            spec.beginMs?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
            spec.endMs?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            if (spec.allDay) putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            spec.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            spec.notes?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val whenWords = when {
            spec.beginMs == null -> "часу не поставив — обери його в редакторі"
            spec.allDay -> "цілий день ${dayOf(spec.beginMs)}"
            else -> "${dayOf(spec.beginMs)} о ${timeOf(spec.beginMs)}"
        }
        return try {
            launch(intent)
            ToolResult.Ok(
                output = "Відкрив чернетку події «${spec.title}» — $whenWords. Перевір час і збережи сам.",
                structured = buildJsonObject {
                    put("title", spec.title)
                    spec.beginMs?.let { put("begin_ms", it) }
                    spec.endMs?.let { put("end_ms", it) }
                    put("when", whenWords)
                    put("all_day", spec.allDay)
                    spec.location?.let { put("location", it) }
                    put("saved", false)
                    put("delivery", "редактор події відкрито; зберігає оператор")
                },
                durationMs = dur(),
            )
        } catch (e: Exception) {
            handoffFailure(
                e,
                "no_calendar_app",
                "На пристрої немає календаря. Постав нагадування в Nightpost (reminder.add) — воно живе всередині застосунку.",
                dur(),
            )
        }
    }

    private fun dayOf(epochMs: Long): String =
        java.text.SimpleDateFormat("d MMMM", java.util.Locale("uk", "UA"))
            .format(java.util.Date(epochMs))

    private fun timeOf(epochMs: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale("uk", "UA"))
            .format(java.util.Date(epochMs))
}

// ---------------------------------------------------------------------------
// phone.panel
// ---------------------------------------------------------------------------

internal enum class PanelKind(val label: String) {
    WIFI("Wi-Fi"),
    INTERNET("інтернет"),
    VOLUME("гучність"),
    NFC("NFC"),
    BLUETOOTH("Bluetooth"),
}

internal fun parsePanelArgs(args: JsonObject): Parsed<PanelKind> {
    val raw = args.str("panel")
        ?: return bad("Не назвав панелі. Є wifi, internet, volume, nfc, bluetooth — обери одну.")
    return when (raw.lowercase()) {
        "wifi", "wi-fi" -> Parsed.Ok(PanelKind.WIFI)
        "internet", "connectivity" -> Parsed.Ok(PanelKind.INTERNET)
        "volume", "звук" -> Parsed.Ok(PanelKind.VOLUME)
        "nfc" -> Parsed.Ok(PanelKind.NFC)
        "bluetooth", "bt" -> Parsed.Ok(PanelKind.BLUETOOTH)
        else -> bad("Панелі «$raw» немає. Є wifi, internet, volume, nfc, bluetooth. Ліхтарик і яскравість — у settings.")
    }
}

/**
 * `phone.panel` — system settings panels (Q+), never silent toggles.
 * Bluetooth has no Settings.Panel — opens the full BT settings screen.
 */
class PhonePanelTool(
    private val launch: (Intent) -> Unit,
) : Tool {

    override val id = "phone.panel"

    override val description =
        "Відкрити системну панель налаштувань поверх застосунку: panel = wifi | internet | volume | nfc | bluetooth. " +
            "Нічого не перемикає само — оператор тисне перемикач у панелі."

    override val argsSchema: JsonObject = buildJsonObject {
        put("panel", JsonPrimitive("string"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LAUNCHER)
    override val risk: ToolRisk = ToolRisk.DEVICE
    override val streaming = false
    override val manifest = phoneManifest("tool.phone.panel", "Системна панель")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        if (ToolPermission.LAUNCHER !in context.approvedPermissions) {
            return ToolResult.PermissionDenied(ToolPermission.LAUNCHER, dur())
        }
        val kind = when (val p = parsePanelArgs(args)) {
            is Parsed.Bad -> return ToolResult.Err("bad_args", p.message, dur())
            is Parsed.Ok -> p.value
        }
        val action = when (kind) {
            PanelKind.WIFI -> Settings.Panel.ACTION_WIFI
            PanelKind.INTERNET -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            PanelKind.VOLUME -> Settings.Panel.ACTION_VOLUME
            PanelKind.NFC -> Settings.Panel.ACTION_NFC
            PanelKind.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
        }
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try {
            launch(intent)
            ToolResult.Ok(
                output = "Відкрив панель «${kind.label}» — перемикай сам, я нічого не змінював.",
                structured = buildJsonObject {
                    put("panel", kind.name.lowercase())
                    put("panel_label", kind.label)
                    put("changed", false)
                },
                durationMs = dur(),
            )
        } catch (e: Exception) {
            handoffFailure(
                e,
                "no_panel",
                "Панель «${kind.label}» на цьому пристрої недоступна (стара версія Android або вимкнена вендором). " +
                    "Відкрий Налаштування вручну.",
                dur(),
            )
        }
    }
}
