package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * Власна мережа нарешті доступна помічникові.
 *
 * Він умів подзвонити й зробити чернетку SMS, але не міг написати людині через
 * PHANTOM — мережу самого продукту. Тут це виправлено, і рівно в тій межі, яку
 * тримає `phone.sms`: **лист не йде сам**. Помічник готує його й відкриває
 * розмову, надсилає оператор.
 *
 * Типи мережі сюди не тягнуться — вузол передається лямбдами, тож
 * `core-symbiote` не набуває нової залежності.
 */

/** Одна людина так, як її бачить помічник. */
data class ReachablePerson(
    val id: String,
    val name: String,
    val live: Boolean,
    val lastSeenMs: Long,
    val verified: Boolean,
)

private fun peopleManifest(id: String, name: String, consent: Boolean) = FeatureManifest(
    id = id,
    tier = 1,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = consent,
    abusableAgainstWeak = false,
    sunsetAfterDaysIdle = 180,
    surfaces = setOf(FeatureManifest.Surface.STREAM),
)

/** «поруч» / «востаннє 20 хв тому» / «ще не бачив». Ніколи не голий epoch. */
private fun lastSeenLabel(lastSeenMs: Long, nowMs: Long): String {
    if (lastSeenMs <= 0L) return "ще не бачив"
    val minutes = ((nowMs - lastSeenMs) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> "щойно"
        minutes < 60L -> "$minutes хв тому"
        minutes < 24L * 60L -> "${minutes / 60L} год тому"
        else -> "${minutes / (24L * 60L)} дн тому"
    }
}

private fun stateWords(p: ReachablePerson, nowMs: Long): String {
    val presence = if (p.live) "чути зараз" else "не чути, востаннє ${lastSeenLabel(p.lastSeenMs, nowMs)}"
    val key = if (p.verified) "ключ звірено" else "ключ НЕ звірено"
    return "$presence · $key"
}

/** `people.list` — кого взагалі можна дістати і хто зараз поруч. */
class PeopleListTool(
    private val roster: suspend () -> List<ReachablePerson>,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Tool {

    override val id = "people.list"

    override val description =
        "Список людей у мережі Nightpost: імʼя, чи чути їх зараз, коли бачив востаннє, " +
            "чи звірено ключ. Використовуй, щоб дізнатись, кому взагалі можна написати. " +
            "«Чути» — не обіцянка доставки; «ключ не звірено» — особу ніхто не підтверджував."

    override val argsSchema: JsonObject = buildJsonObject {
        put("liveOnly", JsonPrimitive("boolean?"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming = false
    override val manifest = peopleManifest("tool.people.list", "Хто на звʼязку", consent = false)

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val liveOnly = args.str("liveOnly")?.toBooleanStrictOrNull() ?: false
        val all = runCatching { roster() }.getOrElse {
            return ToolResult.Err(
                "roster_unreadable",
                "Не вдалось прочитати реєстр людей (${it.message ?: "без деталей"}). " +
                    "Спробуй ще раз; якщо повториться — відкрий екран «Люди», він читає той самий реєстр.",
                elapsed(start),
            )
        }
        val now = nowMs()
        val shown = if (liveOnly) all.filter { it.live } else all
        if (shown.isEmpty()) {
            return ToolResult.Ok(
                output = if (liveOnly) {
                    "Зараз нікого не чути. У реєстрі ${all.size} — напиши, коли зʼявляться."
                } else {
                    "У реєстрі ще нікого немає. Додати людину можна на екрані «Люди» — обміном ключами поруч."
                },
                structured = buildJsonObject {
                    put("people", buildJsonArray { })
                    put("results", buildJsonArray { })
                    put("total", all.size)
                    put("live_count", all.count { it.live })
                    put("filter", if (liveOnly) "лише ті, кого чути" else "усі")
                },
                durationMs = elapsed(start),
            )
        }
        return ToolResult.Ok(
            output = shown.joinToString("; ") { "${it.name} — ${stateWords(it, now)}" },
            structured = buildJsonObject {
                put("total", all.size)
                put("live_count", all.count { it.live })
                put("filter", if (liveOnly) "лише ті, кого чути" else "усі")
                put("source", "реєстр цього пристрою; мережі ніхто не опитував")
                put(
                    "people",
                    buildJsonArray {
                        shown.forEach { p ->
                            add(
                                buildJsonObject {
                                    put("id", p.id)
                                    put("name", p.name)
                                    put("live", p.live)
                                    put("last_seen_ms", p.lastSeenMs)
                                    put("last_seen", lastSeenLabel(p.lastSeenMs, now))
                                    put("verified", p.verified)
                                    put("state", stateWords(p, now))
                                },
                            )
                        }
                    },
                )
                // Ті самі рядки під іменами, які читає картковий шар (title/snippet).
                put(
                    "results",
                    buildJsonArray {
                        shown.forEach { p ->
                            add(
                                buildJsonObject {
                                    put("title", p.name)
                                    put("snippet", stateWords(p, now))
                                },
                            )
                        }
                    },
                )
            },
            durationMs = elapsed(start),
        )
    }
}

/**
 * `people.draft` — приготувати лист і відкрити месенджер.
 *
 * Свідомо НЕ надсилає. Лист від імені оператора — це дія назовні, яку не можна
 * відкотити, і на цій мережі вона ще й підписана його ключем.
 *
 * До 06.09 інструмент казав «відкрив розмову, текст готовий». Це перестало бути
 * правдою, коли з застосунку пішов рідний екран розмови: месенджер тепер веб, і
 * його зібраний бандл має один маршрут — адреси конкретної розмови в нього
 * немає, тож укласти текст у поле нікуди. Слова приведено до того, що справді
 * стається: текст лягає в буфер, месенджер відчиняється, вставляє оператор.
 */
class PeopleDraftTool(
    private val roster: suspend () -> List<ReachablePerson>,
    /** Кладе текст туди, звідки оператор його вставить, і відчиняє месенджер. */
    private val prepareDraft: suspend (peerId: String, text: String) -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Tool {

    override val id = "people.draft"

    override val description =
        "Приготувати лист людині в мережі Nightpost: кладе текст у буфер обміну — " +
            "оператор вставляє й надсилає сам. Лист НІКОЛИ не йде сам. " +
            "Буфер ОДИН: клич по одному листу за раз, бо другий виклик затре перший. " +
            "Аргумент who — імʼя зі списку people.list. Якщо ключ співрозмовника не звірено, " +
            "інструмент скаже це словами — переказуй оператору, не ковтай."

    override val argsSchema: JsonObject = buildJsonObject {
        put("who", JsonPrimitive("string"))
        put("text", JsonPrimitive("string"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.OUTWARD // не надсилає, але кладе текст поза застосунок
    override val streaming = false
    override val manifest = peopleManifest("tool.people.draft", "Чернетка листа", consent = false)

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val who = args.str("who")?.trim().orEmpty()
        val text = args.str("text")?.trim().orEmpty()
        if (who.isEmpty()) {
            return ToolResult.Err(
                "bad_args",
                "Не сказав, кому писати. Передай who — імʼя зі списку people.list.",
                elapsed(start),
            )
        }
        if (text.isEmpty()) {
            return ToolResult.Err(
                "bad_args",
                "Порожню чернетку не відкриваю. Передай text — те, що має побачити оператор перед надсиланням.",
                elapsed(start),
            )
        }

        val all = runCatching { roster() }.getOrElse {
            return ToolResult.Err(
                "roster_unreadable",
                "Не вдалось прочитати реєстр людей (${it.message ?: "без деталей"}), тож не знаю, кому це. " +
                    "Спробуй ще раз або відкрий екран «Люди».",
                elapsed(start),
            )
        }
        return when (val found = PeopleMatch.one(all, who)) {
            is PeopleMatch.None -> ToolResult.Err(
                "no_such_person",
                "«$who» немає в реєстрі" + hintOf(all) + ". Назви імʼя зі списку — або додайте одне одного " +
                    "на екрані «Люди», якщо цієї людини там ще нема.",
                elapsed(start),
            )
            // Двоє з одним іменем — це не край, це «Мама» й два Олеги на роботі.
            // Вгадати тут означає надіслати не тій людині.
            is PeopleMatch.Many -> ToolResult.Err(
                "ambiguous",
                "«$who» — це кілька людей: " + found.names.joinToString(", ") +
                    ". Скажи точніше, кому саме, — вгадувати тут не буду.",
                elapsed(start),
            )
            is PeopleMatch.One -> {
                val person = found.person
                val ready = runCatching { prepareDraft(person.id, text) }.getOrDefault(false)
                if (!ready) {
                    ToolResult.Err(
                        "no_clipboard",
                        "Не вдалось покласти текст у буфер, тож нічого не готово. Ось він, перенеси сам: «$text»",
                        elapsed(start),
                    )
                } else {
                    ToolResult.Ok(
                        output = buildString {
                            // «Месенджер відкрито» тут не кажемо: успіх міряний
                            // буфером, а екран відчиняється липким запитом, який
                            // нікуди не приведе, якщо графа зараз немає на склі.
                            append("Текст для ${person.name} у буфері — відкрий із нею розмову, встав і надішли. ")
                            append(if (person.verified) "Ключ звірено. " else "Ключ НЕ звірено — це може бути не та людина. ")
                            append(if (person.live) "Зараз її чути." else "Зараз її не чути (${lastSeenLabel(person.lastSeenMs, nowMs())}) — надісланий лист чекатиме, доки зʼявиться.")
                        },
                        structured = buildJsonObject {
                            put("peer_id", person.id)
                            put("peerId", person.id)
                            put("name", person.name)
                            put("text", text)
                            put("sent", false)
                            put("verified", person.verified)
                            put("key_state", if (person.verified) "ключ звірено" else "ключ не звірено")
                            put("live", person.live)
                            put("delivery", if (person.live) "співрозмовника чути" else "співрозмовника не чути — доставка відкладена")
                        },
                        durationMs = elapsed(start),
                    )
                }
            }
        }
    }

    private fun hintOf(all: List<ReachablePerson>): String =
        if (all.isEmpty()) "" else ". Є: " + all.take(6).joinToString(", ") { it.name }
}

/** Кого мав на увазі оператор. Чисте, бо помилитись тут — надіслати не тому. */
object PeopleMatch {

    sealed interface Result
    data object None : Result
    data class One(val person: ReachablePerson) : Result
    data class Many(val names: List<String>) : Result

    fun one(people: List<ReachablePerson>, query: String): Result {
        val q = normalize(query)
        if (q.isEmpty()) return None
        val exact = people.filter { normalize(it.name) == q }
        if (exact.size == 1) return One(exact.single())
        if (exact.size > 1) return Many(exact.map { it.name })
        val partial = people.filter { normalize(it.name).contains(q) }
        return when {
            partial.isEmpty() -> None
            partial.size == 1 -> One(partial.single())
            else -> Many(partial.map { it.name })
        }
    }

    private fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").lowercase()
}

private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotBlank() }?.content
