package local.phantom.companion.core.ai.route

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.tool.MeetupAsk
import local.phantom.companion.core.ai.tool.MeetupSpeech
import local.phantom.companion.core.ai.tool.MoneyAsk
import local.phantom.companion.core.ai.tool.MoneyTool
import local.phantom.companion.core.ai.tool.ProductAsk
import local.phantom.companion.core.ai.tool.ProductSpeech
import java.util.UUID

/**
 * Без моделі: впізнає прохання до інструмента за словами і показує те, що
 * інструмент повернув. Нічого не генерує й не переказує.
 */
class OnDeviceToolRules {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Хід закінчується результатами інструментів: показати кожен результат останнього виклику, як є і по черзі. */
    fun toolOutcome(intent: AIIntent.Text): String? =
        outcomes(intent.history.takeLastWhile { it.role == AIIntent.Text.Role.TOOL }.map { it.content })

    /** Результати, які вже повернули інструменти, — без моделі, тим самим видом, що й [toolOutcome]. */
    fun outcomes(results: List<String>): String? =
        results.map(::render).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }

    fun toolCall(intent: AIIntent.Text): AIChunk.ToolCall? {
        val offered = intent.tools.map { it.name }.toSet()
        return detect(intent.prompt.trim())?.takeIf { it.name in offered }
    }

    /** Високовпевнені прохання (нагадування, памʼять, гроші, зустріч): відповідь правил іде до моделі. */
    fun firstCall(intent: AIIntent.Text): AIChunk.ToolCall? {
        val prompt = intent.prompt.trim()
        if (prompt.isEmpty() || '?' in prompt || intent.history.lastOrNull()?.role == AIIntent.Text.Role.TOOL) return null
        val offered = intent.tools.map { it.name }.toSet()
        fun named(vararg candidates: String): String? = candidates.firstOrNull { it in offered }

        when (val ask = ProductSpeech.read(prompt)) {
            is ProductAsk.Remind -> {
                val tool = named(REMINDER_ADD, REMINDER_SET) ?: return null
                val args = buildJsonObject {
                    put("text", ask.text)
                    ask.inMinutes?.let { put("in_minutes", it) }
                    ask.localTime?.let { put("local_time", it) }
                }.toString()
                return ruleCall(tool, args)
            }
            is ProductAsk.Remember -> {
                val tool = named(MEMORY_SAVE, MEMORY_REMEMBER) ?: return null
                val args = buildJsonObject { put("text", ask.text) }.toString()
                return ruleCall(tool, args)
            }
            null -> Unit
        }

        val money = MoneyTool.read(prompt)
        if (money is MoneyAsk.Pay && money.amount != null && money.coin != null && (money.who.isNotEmpty() || money.address != null)) {
            val tool = named(PAY_DRAFT) ?: return null
            val args = buildJsonObject {
                put("who", JsonArray(money.who.map(::JsonPrimitive)))
                put("amount", money.amount.toPlainString())
                put("coin", money.coin)
                money.address?.let { put("address", it) }
                money.note?.let { put("note", it) }
            }.toString()
            return ruleCall(tool, args)
        }

        when (val ask = MeetupSpeech.read(prompt)) {
            is MeetupAsk.Create -> {
                val tool = named(MEETUP) ?: return null
                return ruleCall(tool, meetupArguments(ask))
            }
            is MeetupAsk.Split -> {
                val tool = named(BILL_SPLIT) ?: return null
                return ruleCall(tool, splitArguments(ask))
            }
            null -> Unit
        }

        return null
    }

    /** Хід після результатів, які викликали самі правила: слова інструмента — і є відповідь. */
    fun answersItself(intent: AIIntent.Text): Boolean {
        val history = intent.history
        val toolTail = history.takeLastWhile { it.role == AIIntent.Text.Role.TOOL }
        if (toolTail.isEmpty()) return false
        val before = history.getOrNull(history.size - toolTail.size - 1) ?: return false
        if (before.role != AIIntent.Text.Role.ASSISTANT) return false
        val calls = before.toolCalls?.takeIf { it.isNotEmpty() } ?: return false
        return calls.all { it.id.startsWith(RULE_CALL_PREFIX) } && toolOutcome(intent) != null
    }

    /**
     * Чи візьмуть це правила самі, без моделі. Питають ті, хто відповідає
     * ПЕРЕД ланцюгом (памʼять PHANTOM): «розуму немає» на «де укриття» —
     * неправда, бо інструмент на телефоні є.
     */
    fun recognizes(prompt: String): Boolean = toolFor(prompt) != null || ProductSpeech.read(prompt.trim()) != null

    /** Який інструмент правила впізнали б у цьому питанні. */
    fun toolFor(prompt: String): String? = detect(prompt.trim())?.name

    /** Питання про сам телефон: памʼять про людину тут не свідок, відповідь — за інструментом. */
    fun asksPhone(prompt: String): Boolean =
        recognizes(prompt) || prompt.lowercase().let { lower -> PHONE_DATA.any { it in lower } }

    internal fun render(raw: String): String {
        val trimmed = raw.trim()
        val obj = parseObject(trimmed) ?: return trimmed
        // Конверт ToolBatchTurns: {"ok":…, "output":"…"}; показуємо вміст, не конверт.
        val output = (obj["output"] as? JsonPrimitive)?.contentOrNull
        if (output != null && obj.keys.all { it == "ok" || it == "output" }) {
            val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull ?: true
            val body = parseObject(output.trim())?.let(::lines) ?: output.trim()
            return if (ok) body else "$FAILED $body"
        }
        return lines(obj).ifEmpty { trimmed }
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun lines(obj: JsonObject): String = obj.entries.mapNotNull { (key, value) ->
        val shown = when (value) {
            is JsonPrimitive -> value.contentOrNull
            else -> value.toString()
        }?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
        "• ${LABELS[key] ?: key}: $shown"
    }.joinToString("\n")

    private fun detect(prompt: String): AIChunk.ToolCall? {
        val lower = prompt.lowercase()

        // Зустріч і рахунок — першими: у «збери Олену в кафе поруч о 19:00» є «поруч», яке інакше
        // забрав би пошук поблизу. Читач скупий (див. MeetupSpeech), тож звичайні «збери…» сюди не потрапляють.
        when (val ask = MeetupSpeech.read(prompt)) {
            is MeetupAsk.Create -> return call(MEETUP, meetupArguments(ask))
            is MeetupAsk.Split -> return call(BILL_SPLIT, splitArguments(ask))
            null -> Unit
        }

        if (matchesAny(lower, "укритт", "бомбосховищ", "сховищ", "де сховати", "де укрити", "сирен", "тривог", "обстріл")) {
            return call(SHELTER)
        }

        if (matchesAny(lower, "маршрут", "як доїхати", "як дістатись", "як дістатися", "проклади дорогу", "проклади маршрут", "шлях до", "навігаці")) {
            val destination = extractDestination(prompt) ?: return null
            return call(ROUTE, buildJsonObject { put("to", destination) }.toString())
        }

        if (matchesAny(lower, "поруч", "поблизу", "де знайти", "де аптек", "де лікарн", "де магазин", "де заправк", "де азс", "джерело води")) {
            return call(NEARBY, buildJsonObject { put("category", extractQuery(prompt)) }.toString())
        }

        if (matchesAny(lower, "аналіз карти", "проаналізуй карту", "зон.*небезпек", "радіус ураження", "сектор обстрілу", "огляд карти")) {
            return call(ANALYZE)
        }

        if (matchesAny(lower, "покажи карту", "відкрий карту", "перейди до карти", "де це на карті")) {
            return call(SHOW)
        }

        if (matchesAny(lower, "погод", "температур", "дощ", "сніг", "вітер", "метео", "прогноз")) {
            return call(WEATHER)
        }

        if (matchesAny(lower, "відкрий", "запусти")) {
            // Невпізнаний додаток раніше відкривав Налаштування — тиха не та дія.
            val app = extractAppName(lower) ?: return null
            return call(LAUNCH, buildJsonObject { put("query", app) }.toString())
        }

        if (matchesAny(lower, "список додатків", "які додатки", "встановлені програми", "покажи програми")) {
            return call(APPS)
        }

        return null
    }

    private fun meetupArguments(ask: MeetupAsk.Create): String = buildJsonObject {
        if (ask.people.isNotEmpty()) put("people", JsonArray(ask.people.map(::JsonPrimitive)))
        ask.place?.let { put("place", it) }
        ask.at?.let { put("at", it) }
    }.toString()

    private fun splitArguments(ask: MeetupAsk.Split): String =
        buildJsonObject { put("mode", ask.mode) }.toString()

    private fun ruleCall(name: String, arguments: String): AIChunk.ToolCall = AIChunk.ToolCall(
        name = name,
        arguments = arguments,
        callId = RULE_CALL_PREFIX + UUID.randomUUID().toString().replace("-", "").take(8),
        route = Route.ON_DEVICE_RULES,
    )

    private fun call(name: String, arguments: String = "{}"): AIChunk.ToolCall = AIChunk.ToolCall(
        name = name,
        arguments = arguments,
        callId = "call_" + UUID.randomUUID().toString().take(8),
        route = Route.ON_DEVICE_RULES,
    )

    private fun matchesAny(text: String, vararg patterns: String): Boolean =
        patterns.any { text.contains(it.toRegex(RegexOption.IGNORE_CASE)) }

    private fun extractDestination(prompt: String): String? {
        var cleaned = prompt
        for (lead in DESTINATION_LEADS) cleaned = cleaned.replace(lead, "", ignoreCase = true)
        return cleaned.trim().takeIf { it.isNotBlank() }
    }

    private fun extractQuery(prompt: String): String = when {
        prompt.contains("аптек", ignoreCase = true) -> "аптека"
        prompt.contains("лікарн", ignoreCase = true) -> "лікарня"
        prompt.contains("магазин", ignoreCase = true) -> "магазин"
        prompt.contains("заправк", ignoreCase = true) || prompt.contains("азс", ignoreCase = true) -> "заправка"
        prompt.contains("вод", ignoreCase = true) -> "питна вода"
        else -> prompt.trim()
    }

    private fun extractAppName(lower: String): String? = when {
        lower.contains("камер") -> "camera"
        lower.contains("браузер") -> "browser"
        lower.contains("налаштування") -> "settings"
        lower.contains("диктофон") -> "recorder"
        lower.contains("галере") -> "gallery"
        lower.contains("телеграм") || lower.contains("telegram") -> "telegram"
        else -> null
    }

    companion object {
        // Імена — ті, під якими інструменти стоять у реєстрі (`Tool.id`), а не
        // імена класів: «map.nearest_shelter» і «map.route_live» реєстр не знав,
        // і `toolCall` мовчки відкидав саме укриття й маршрут (збірка 16).
        const val SHELTER = "shelter.nearest"
        const val ROUTE = "map.route"
        const val NEARBY = "map.nearby"
        const val ANALYZE = "map.analyze"
        const val SHOW = "map.show"
        const val WEATHER = "weather.now"
        const val LAUNCH = "launch"
        const val APPS = "apps.list"
        const val MEETUP = "meetup.create"
        const val BILL_SPLIT = "bill.split"
        const val PAY_DRAFT = "pay.draft"
        const val REMINDER_ADD = "reminder.add"
        const val REMINDER_SET = "reminder.set"
        const val MEMORY_SAVE = "memory.save"
        const val MEMORY_REMEMBER = "memory.remember"
        const val RULE_CALL_PREFIX = "rule_"

        /** Усе, що правила вміють покликати; сторож у :app звіряє це з реєстром. */
        val TOOL_IDS = setOf(SHELTER, ROUTE, NEARBY, ANALYZE, SHOW, WEATHER, LAUNCH, APPS, MEETUP, BILL_SPLIT)

        private const val FAILED = "Інструмент не впорався:"

        private val PHONE_DATA = listOf(
            "календар", "нагадуван", "події", "подій", "додатк", "застосунк", "контакт", "батаре",
            "calendar", "reminder", "my apps", "installed apps", "contacts", "battery",
            // День, гроші, люди й дорога лежать у застосунку: памʼять про людину тут теж не свідок.
            "сьогодні", "завтра", "план", "розклад", "зустріч", "справ",
            "рахунк", "баланс", "гаманц", "грош", "балів", "витрат", "винен", "винна",
            "друз", "близьк", "поруч", "автобус", "трамва", "зупинк", "погод",
        )

        private val DESTINATION_LEADS = listOf(
            "проклади маршрут до", "проклади дорогу до", "як доїхати до",
            "як дістатися до", "як дістатись до", "шлях до", "маршрут до",
        )

        private val LABELS = mapOf(
            "name" to "Назва",
            "title" to "Назва",
            "address" to "Адреса",
            "type" to "Тип",
            "distance" to "Відстань",
            "dist_m" to "Відстань, м",
            "distance_m" to "Відстань, м",
            "duration_s" to "Час, с",
            "temperature" to "Температура",
            "wind_speed" to "Вітер",
            "description" to "Опис",
            "stdout" to "Вивід",
            "stderr" to "Помилки",
            "exitCode" to "Код виходу",
        )
    }
}
