package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.tool.MoneyTool
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * Зустріч і рахунок — з голосу помічника.
 *
 * «Збери Олену й Петра в Cafe X о 19:00» і «розділи рахунок»: інструмент
 * впізнає людей у реєстрі, кладе назване в чернетку і просить месенджер її
 * відкрити. Далі все робить людина: «Запросити» натискає вона, платить —
 * своїм відбитком. У цього файлу немає жодних дверей до надсилання, і
 * `MeetupToolsContractTest` це стереже.
 *
 * Месенджер, мережа й мова відповіді сюди не тягнуться: чернетку відкриває
 * лямбда `open`, слова дає [MeetupWords] — їх збирає застосунок за мовою
 * месенджера (`ReplyLanguage`), а не ця бібліотека.
 */

/** Що помічник просить відкрити у месенджері. */
sealed interface MeetupDraftAsk {
    /** Чернетка зустрічі: імена так, як лягають у чернетку; заклад і час — як сказано. */
    data class Meetup(val people: List<String>, val place: String?, val at: String?) : MeetupDraftAsk

    /** «Розділи рахунок»: `claimed` — кожен відмічає свої страви, `equal` — порівну. */
    data class Split(val mode: String) : MeetupDraftAsk
}

/** Слова обох інструментів мовою месенджера. */
interface MeetupWords {
    /** Чернетка складена. [people] — лише ті, кого впізнано в реєстрі. */
    fun drafted(people: List<String>, place: String?, at: String?): String

    /** Нічого не назвали: відкрито порожню чернетку. */
    fun emptyDraft(): String

    /** «Не знайшов «Богдан» серед людей у ваших розмовах.» */
    fun notFound(name: String): String

    /** Це імʼя мають кілька людей; вгадувати не можна. */
    fun which(name: String, candidates: List<String>): String

    fun splitOpened(mode: String): String
    fun rosterUnreadable(): String
    fun draftFailed(): String
    fun badMode(mode: String): String
}

private fun meetupManifest(id: String, name: String) = FeatureManifest(
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

/**
 * `meetup.create` — чернетка зустрічі в месенджері.
 *
 * Свідомо НЕ запрошує. Запрошення створює групу й шле картку від імені людини
 * кільком іншим — це дія назовні, її робить сама людина кнопкою «Запросити».
 */
class MeetupCreateTool(
    private val roster: suspend () -> List<ReachablePerson>,
    /** Просить месенджер відкрити чернетку. Нічого не надсилає. */
    private val open: suspend (MeetupDraftAsk) -> Unit,
    private val words: () -> MeetupWords,
) : Tool {

    override val id = "meetup.create"

    override val description =
        "Чернетка зустрічі в месенджері Nightpost: відкриває її заповненою, запрошує і надсилає лише людина " +
            "кнопкою «Запросити». people — імена так, як їх сказав оператор (відмінок не важливий); " +
            "place — назва закладу; at — час: «19:00» або «завтра 19:00». " +
            "Чого оператор не казав, того не вигадуй: поле лишай порожнім, воно заповниться в чернетці. " +
            "Не знайдену людину інструмент назве сам — перекажи це оператору."

    override val argsSchema: JsonObject = buildJsonObject {
        put("people", JsonPrimitive("array?"))
        put("place", JsonPrimitive("string?"))
        put("at", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = emptySet()

    // Чернетка відкривається всередині застосунку й нікуди не йде: питати згоди нема про що.
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming = false
    override val manifest = meetupManifest("tool.meetup.create", "Чернетка зустрічі")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val say = words()
        val said = saidPeople(args)
        val place = args.text("place")
        val at = args.text("at")

        val all = runCatching { roster() }.getOrElse {
            return ToolResult.Err("roster_unreadable", say.rosterUnreadable(), elapsed(start))
        }

        val found = ArrayList<String>()
        val unknown = ArrayList<String>()
        val unclear = ArrayList<Pair<String, List<String>>>()
        for (name in said) {
            when (val hit = MeetupPeople.find(all, name)) {
                is MeetupPeople.Hit.One -> if (hit.person.name !in found) found += hit.person.name
                is MeetupPeople.Hit.Nobody -> unknown += name
                is MeetupPeople.Hit.Several -> unclear += name to hit.names
            }
        }
        // Знайдених кладемо як у реєстрі. Незнайомих і двозначних — як сказано: веб покаже їх
        // серед «не знайдених» і дасть обрати руками, а тут їх мовчки не губимо й не підміняємо.
        val ask = MeetupDraftAsk.Meetup(
            people = (found + unknown + unclear.map { it.first }).distinct(),
            place = place,
            at = at,
        )
        runCatching { open(ask) }.getOrElse {
            return ToolResult.Err("draft_failed", say.draftFailed(), elapsed(start))
        }

        val output = buildList {
            add(if (said.isEmpty() && place == null && at == null) say.emptyDraft() else say.drafted(found, place, at))
            unknown.forEach { add(say.notFound(it)) }
            unclear.forEach { (name, names) -> add(say.which(name, names)) }
        }.joinToString("\n")

        return ToolResult.Ok(
            output = output,
            structured = buildJsonObject {
                put("draft", true)
                // Ніщо не надіслано й нікого не запрошено: це лише чернетка в месенджері.
                put("sent", false)
                put("people", JsonArray(found.map(::JsonPrimitive)))
                put("missing", JsonArray(unknown.map(::JsonPrimitive)))
                put(
                    "ambiguous",
                    buildJsonArray {
                        unclear.forEach { (name, names) ->
                            add(
                                buildJsonObject {
                                    put("name", name)
                                    put("candidates", JsonArray(names.map(::JsonPrimitive)))
                                },
                            )
                        }
                    },
                )
                place?.let { put("place", it) }
                at?.let { put("at", it) }
                put("source", "реєстр цього пристрою")
            },
            durationMs = elapsed(start),
        )
    }

    /** Люди з аргументів: масив імен або один рядок «Олену, Петра і Марту». */
    private fun saidPeople(args: JsonObject): List<String> {
        val raw = when (val v = args["people"]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            is JsonPrimitive -> v.content.split(Regex("""\s*(?:,|;|\s(?:і|й|та|and)\s)\s*"""))
            else -> emptyList()
        }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

/**
 * `bill.split` — «розділи рахунок»: відкрити розподіл рахунку в розмові.
 *
 * Нічого не платить: платить кожен сам, своїм відбитком, зі своєї частки.
 */
class BillSplitTool(
    /** Просить месенджер відкрити рахунок. Нічого не надсилає й не платить. */
    private val open: suspend (MeetupDraftAsk) -> Unit,
    private val words: () -> MeetupWords,
) : Tool {

    override val id = "bill.split"

    override val description =
        "Розподіл рахунку в відкритій розмові месенджера Nightpost: кожен відмічає свої страви, частки рахуються " +
            "з відміток. Нічого не платить і не надсилає. " +
            "mode: claimed — за відмітками (типово) або equal — оператор просив порівну."

    override val argsSchema: JsonObject = buildJsonObject {
        put("mode", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming = false
    override val manifest = meetupManifest("tool.bill.split", "Розподіл рахунку")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val say = words()
        val said = args.text("mode")
        val mode = when (said?.lowercase()) {
            null, "claimed", "dishes", "items", "my", "за відмітками", "за стравами" -> "claimed"
            "equal", "equally", "even", "evenly", "порівну", "навпіл", "рівно" -> "equal"
            else -> return ToolResult.Err("bad_args", say.badMode(said.orEmpty()), elapsed(start))
        }
        runCatching { open(MeetupDraftAsk.Split(mode)) }.getOrElse {
            return ToolResult.Err("draft_failed", say.draftFailed(), elapsed(start))
        }
        return ToolResult.Ok(
            output = say.splitOpened(mode),
            structured = buildJsonObject {
                put("draft", true)
                put("mode", mode)
                put("sent", false)
                put("paid", false)
            },
            durationMs = elapsed(start),
        )
    }
}

/** Хто з реєстру мався на увазі. Чисте: помилка тут — запросити не ту людину. */
internal object MeetupPeople {

    sealed interface Hit {
        data class One(val person: ReachablePerson) : Hit
        data object Nobody : Hit
        data class Several(val names: List<String>) : Hit
    }

    fun find(people: List<ReachablePerson>, said: String): Hit {
        val q = normalize(said)
        if (q.isEmpty()) return Hit.Nobody
        // Точне імʼя виграє у схожих: «Олег Мельник» — це він, а не обидва Олеги.
        val exact = people.filter { normalize(it.name) == q }
        if (exact.size == 1) return Hit.One(exact.single())
        if (exact.size > 1) return Hit.Several(exact.map { it.name })
        // Відмінок («Олену», «Петра», «Оленою») звіряє той самий читач, що й для переказів.
        val words = q.split(' ')
        val named = people.filter { MoneyTool.nameMatches(words, it.name) }
        return when (named.size) {
            0 -> Hit.Nobody
            1 -> Hit.One(named.single())
            else -> Hit.Several(named.map { it.name })
        }
    }

    private fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ").lowercase()
}

private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
