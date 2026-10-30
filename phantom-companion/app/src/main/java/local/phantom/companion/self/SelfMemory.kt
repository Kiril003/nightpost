package local.phantom.companion.self

import java.util.UUID
import local.phantom.companion.core.data.memory.FactMention
import local.phantom.companion.core.data.memory.MemoryFactEntity
import local.phantom.companion.core.data.memory.MemoryFactStore
import local.phantom.companion.core.memory.Ledger
import local.phantom.companion.core.memory.LedgerStore
import local.phantom.companion.core.memory.MemoryItem
import local.phantom.companion.core.memory.Provenance
import local.phantom.companion.core.memory.Seal

/**
 * Памʼять PHANTOM про людину — репліка ledger-а ПК (`core-memory`): ті самі
 * правила запису, «неправда»/«забудь», бюджет промпту й синк (SYNC.md).
 *
 * Старий Room `memory_facts` лишається ДЖЕРЕЛОМ ІМПОРТУ: туди досі пишуть
 * інструменти й консолідатор, і все звідти переноситься сюди з тим самим `id`
 * ([importLegacy]). Показується ВСЕ, що є в репліці: «що я про тебе знаю», яке
 * ховає частину, — неправда.
 */
class SelfMemory(
    private val store: MemoryFactStore,
    ledger: Ledger? = null,
    /** Локальна зміна — привід синхронізуватись із ПК. */
    private val onChange: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Без справжнього сховища (тести, збій ключа) — репліка лише в памʼяті процесу. */
    val ledger: Ledger = ledger ?: Ledger(LedgerStore(Seal.fromSecret("ram-" + UUID.randomUUID())), device = "phone") { now() }

    enum class From { SELF, HEARD, GUESS }

    /** [old] — запис зі старих розмов, який не вдалось сказати людським реченням. */
    data class Known(
        val id: String,
        val text: String,
        val from: From,
        val atMs: Long,
        val skill: Boolean,
        val old: Boolean = false,
        val pending: Boolean = false,
        val group: String = "about",
        val subject: String = "",
        val source: String = "owner_said",
        val device: String = "phone",
        val confidence: Double = 1.0,
    )

    /** Слова власника в чаті PHANTOM (SELF — «запамʼятай», HEARD — сказав про себе між іншим). */
    suspend fun remember(fact: SelfSpeech.Fact, from: From): String {
        val predicate = fact.slot?.let { SLOT_PREDICATE[it] ?: it } ?: "about"
        val (outcome, id) = ledger.rememberText(
            fact.text,
            Ledger.Origin.OWNER_CHAT,
            predicate = predicate,
            // однозначний слот (імʼя, вік, місто) — значенням стає речення: нове закриває старе
            value = if (fact.slot != null) fact.text else "",
            source = sourceOf(from),
            confidence = confidenceOf(from),
        )
        if (outcome != Ledger.Outcome.BLOCKED) onChange()
        return id
    }

    suspend fun rememberSkill(topic: String, text: String) {
        val predicate = "skill_$topic"
        ledger.known().filter { it.predicate == predicate }.forEach { ledger.forget(it.id, why = "forget") }
        ledger.rememberText(text, Ledger.Origin.OWNER_CHAT, kind = "procedure", predicate = predicate,
            source = "inferred", confidence = 0.5)
        onChange()
    }

    suspend fun known(): List<Known> {
        importLegacy()
        return ledger.known().map { it.toKnown() }
    }

    /** «Неправда» (типово) блокує зміст назавжди; «забудь» стирає без сліду. */
    suspend fun forget(id: String, why: String = "false"): Boolean {
        val gone = ledger.forget(id, why = why)
        runCatching { store.forget(id) }
        if (gone) onChange()
        return gone
    }

    /** Забуває те, про що людина сказала «забудь …»: найближчий за словами факт. */
    suspend fun forgetMatching(query: String): Known? {
        val tokens = FactMention.tokens(SelfSpeech.flip(query)) + FactMention.tokens(query)
        if (tokens.isEmpty()) return null
        val best = known()
            .map { it to FactMention.score(it.text, tokens.distinct()) }
            .filter { it.second > 0f }
            .maxWithOrNull(compareBy<Pair<Known, Float>> { it.second }.thenBy { it.first.atMs })
            ?.first ?: return null
        forget(best.id, why = "forget")
        return best
    }

    suspend fun forgetAll(): Int {
        val all = known()
        all.forEach { forget(it.id, why = "forget") }
        return all.size
    }

    /**
     * Блок памʼяті для промпту — той самий, що на ПК: профіль, доречне до питання,
     * події, кожен рядок з міткою [m:id], під бюджетом 1500 токенів. Порожньо, коли
     * в памʼяті немає нічого, що могло б піти в розмову.
     */
    suspend fun promptBlock(query: String = "", budgetTokens: Int = PROMPT_BUDGET): String {
        importLegacy()
        if (ledger.promptItems().isEmpty()) return ""
        return ledger.contextForPrompt(query, budgetTokens).text
    }

    /** «Не знаю…» без моделі, коли питання про власника, а опори немає. */
    suspend fun dontKnow(query: String): String? {
        importLegacy()
        return ledger.ungroundedAnswer(query)
    }

    /**
     * Перенесення Room → репліка: кожен рядок один раз, з тим самим `id`
     * (SYNC.md, «Відповідність Room memory_facts»). Забуте в репліці (надгробок)
     * не повертається: `id` уже є.
     */
    suspend fun importLegacy(limit: Int = LIMIT): Int {
        val rows = runCatching { store.list(limit) }.getOrDefault(emptyList())
        var n = 0
        ledger.store.batch {
            for (f in rows) {
                if (ledger.store.get(f.id) != null) continue
                val item = fromRoom(f)
                // «неправда» блокує зміст, звідки б він не прийшов — і зі старої таблиці теж
                if (ledger.store.isTombstoned(item.text, item.subject, item.predicate, item.value)) continue
                ledger.store.save(item)
                n++
            }
        }
        if (n > 0) onChange()
        return n
    }

    private fun fromRoom(f: MemoryFactEntity): MemoryItem {
        val source = when {
            MemoryFactEntity.statedBySelf(f.source) -> "owner_said"
            f.source == "phantom.heard" -> "heard"
            else -> "inferred"
        }
        val skill = f.category.startsWith("$SKILL.")
        val mine = f.category == SELF || f.category.startsWith("$SELF.") || skill
        val slot = f.category.removePrefix("$SELF.").takeIf { f.category.startsWith("$SELF.") }
        val active = source != "inferred" || f.confirmed == MemoryFactEntity.CONFIRMED
        val human = if (mine) null else human(f.text)
        val text = if (mine) f.text.trim() else human ?: f.text.trim()
        return MemoryItem(
            id = f.id,
            kind = if (skill) "procedure" else "fact",
            predicate = when {
                skill -> "skill_" + f.category.removePrefix("$SKILL.")
                slot != null -> SLOT_PREDICATE[slot] ?: slot
                else -> "about"
            },
            value = if (slot != null) text else "",
            text = text,
            provenance = Provenance(source = source, conversationId = "room:${f.category}", ts = f.firstSeenMs, device = "phone"),
            confidence = f.confidence.toDouble().coerceIn(0.0, 1.0),
            status = if (active) "active" else "pending",
            validFrom = f.firstSeenMs,
            lastConfirmed = f.lastConfirmedMs,
            // «старі нотатки»: з інших розмов і не вдалось сказати людським реченням
            legacy = !mine && human == null,
        )
    }

    private fun MemoryItem.toKnown() = Known(
        id = id,
        text = displayText(),
        from = from(source),
        atMs = lastConfirmed,
        skill = kind == "procedure",
        old = legacy,
        pending = status == "pending",
        group = groupOf(this),
        subject = if (subject == MemoryItem.OWNER) "" else subject,
        source = source,
        device = if (provenance.device.startsWith("pc")) "pc" else "phone",
        confidence = confidence,
    )

    companion object {
        private val SUBJECT = Regex("^(?:оператор|користувач|людина|власник)\\s+", RegexOption.IGNORE_CASE)

        /** Слоти `SelfSpeech` → однозначні предикати ledger-а ПК. */
        val SLOT_PREDICATE = mapOf("name" to "name", "age" to "age", "home" to "lives_in", "trade" to "works_as")

        /** Розділ екрана «Памʼять» — той самий поділ, що `routes_memory_self.py::group_of`. */
        fun groupOf(it: MemoryItem): String = when {
            it.status == "pending" -> "pending"
            it.kind == "procedure" -> "skills"
            it.kind == "episode" -> "events"
            it.kind == "place" -> "places"
            it.kind == "person" || it.subject != MemoryItem.OWNER -> "people"
            else -> "about"
        }

        /** Запис з інших розмов — людським реченням до людини, або null. */
        fun human(raw: String): String? {
            val text = raw.trim().trimEnd('.')
            if (text.isEmpty()) return null
            when (val heard = SelfSpeech.read(text)) {
                is SelfSpeech.Heard.Remember -> return heard.fact.text
                is SelfSpeech.Heard.Facts -> if (heard.facts.size == 1) return heard.facts[0].text
                else -> Unit
            }
            SecondPerson.sentence(text)?.let { return it }
            val bare = text.replace(SUBJECT, "")
            val first = bare.substringBefore(' ')
            val verb = SecondPerson.fromThird(first) ?: return null
            val rest = bare.substring(first.length)
            return "Ти ${verb.lowercase()}${SelfSpeech.flip(rest)}"
        }

        const val SELF = "self"
        const val SKILL = "skill"
        private const val LIMIT = 500
        const val PROMPT_BUDGET = Ledger.BUDGET_TOKENS

        fun sourceOf(from: From) = when (from) {
            From.SELF -> "owner_said"
            From.HEARD -> "heard"
            From.GUESS -> "inferred"
        }

        private fun confidenceOf(from: From) = when (from) {
            From.SELF -> 1.0
            From.HEARD -> 0.8
            From.GUESS -> 0.5
        }

        fun from(source: String) = when (source) {
            "owner_said" -> From.SELF
            "heard" -> From.HEARD
            else -> From.GUESS
        }
    }
}
