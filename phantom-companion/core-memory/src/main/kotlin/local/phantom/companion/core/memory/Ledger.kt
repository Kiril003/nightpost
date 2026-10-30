package local.phantom.companion.core.memory

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Репліка ledger-а ПК на телефоні: ті самі правила запису, надгробків і синку,
 * той самий вибір контексту під бюджет — рядок у рядок з `service.py`.
 * Відхилення від ПК ловить `MemoryVectorsTest` (вектори пише Python).
 *
 * Однокористувацька: на телефоні памʼять одного власника.
 */
class Ledger(
    val store: LedgerStore,
    val device: String = "phone",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val vectors = HashMap<String, Pair<Long, DoubleArray>>()

    enum class Outcome(val wire: String) { ADDED("added"), PENDING("pending"), CONFIRMED("confirmed"), SUPERSEDED("superseded"), BLOCKED("blocked") }

    /** Звідки запис має право прийти (`service.py::ORIGINS`). */
    enum class Origin { OWNER_CHAT, OWNER_STATEMENT, MISSION, PEER_MESSAGE }

    class WriteRefused(val origin: Origin) : IllegalArgumentException("write refused for $origin")

    data class Context(val text: String, val itemIds: List<String>, val tokens: Int)

    // ══ запис ════════════════════════════════════════════════════════════
    fun applyCandidate(c: Candidate, prov: Provenance): Pair<Outcome, String> = store.batch {
        val text = c.text.ifEmpty { listOf(c.predicate.replace("_", " "), c.value).filter { it.isNotEmpty() }.joinToString(" ") }
        if (store.isTombstoned(text, c.subject, c.predicate, c.value)) return@batch Outcome.BLOCKED to ""
        var status = if (c.source == "inferred" || c.confidence < PENDING_BELOW) "pending" else "active"
        val t = now()

        val same = if (c.value.isNotEmpty()) store.liveByTriple(c.subject, c.predicate, c.value) else store.liveByText(text)
        if (same.isNotEmpty()) {
            val it = same[0]
            if (rank(c.source) > rank(it.source)) it.provenance.source = c.source
            it.confidence = maxOf(it.confidence, c.confidence)
            it.lastConfirmed = t
            if (it.status == "pending" && status == "active") it.status = "active"
            store.save(it)
            return@batch Outcome.CONFIRMED to it.id
        }

        val item = MemoryItem(
            kind = c.kind, subject = c.subject, predicate = c.predicate, value = c.value, text = text,
            provenance = prov, confidence = c.confidence, status = status, validFrom = t, lastConfirmed = t,
            sensitivity = c.sensitivity,
        )
        if (MemoryItem.exclusive(c.predicate) && c.value.isNotEmpty()) {
            val rivals = store.liveBySlot(c.subject, c.predicate).filter { it.status == "active" }
            if (rivals.any { rank(it.source) > rank(c.source) }) {
                item.status = "pending"
                store.save(item)
                return@batch Outcome.PENDING to item.id
            }
            if (item.status == "active" && rivals.isNotEmpty()) {
                store.save(item)
                for (r in rivals) {
                    r.validTo = t; r.status = "superseded"; r.supersededBy = item.id
                    store.save(r)
                }
                return@batch Outcome.SUPERSEDED to item.id
            }
        }
        status = item.status
        store.save(item)
        (if (status == "pending") Outcome.PENDING else Outcome.ADDED) to item.id
    }

    /** «Неправда, …»: надгробок на все, що збігається, і на сам зміст. */
    fun retract(r: Candidate): List<String> = store.batch {
        val hits = LinkedHashMap<String, MemoryItem>()
        if (r.value.isNotEmpty()) store.liveByTriple(r.subject, r.predicate, r.value).forEach { hits[it.id] = it }
        if (hits.isEmpty() && r.text.isNotEmpty()) store.liveByText(r.text).forEach { hits[it.id] = it }
        if (hits.isEmpty()) {
            val q = Text.normalize(r.text.ifEmpty { r.value })
            for (it in store.liveBySlot(r.subject, r.predicate)) {
                if (r.value.isNotEmpty() && Text.normalize(r.value) in Text.normalize(it.displayText() + " " + it.value)) hits[it.id] = it
            }
            if (hits.isEmpty() && q.isNotEmpty()) {
                for ((score, it) in rank(q, store.list())) if (score >= 0.75) hits[it.id] = it
            }
        }
        val gone = hits.values.filter { forget(it.id) }.map { it.id }
        if (!store.isTombstoned(r.text, r.subject, r.predicate, r.value)) {
            val marker = MemoryItem(
                kind = "fact", subject = r.subject, predicate = r.predicate, value = r.value, text = r.text,
                provenance = Provenance(source = "owner_said", ts = now(), device = device),
                confidence = 1.0, status = "tombstone", validFrom = now(), lastConfirmed = now(),
            )
            marker.validTo = now()
            store.save(marker)
        }
        gone
    }

    /** why="false" — «неправда» (зміст заблоковано); why="forget" — «забудь» (стерто без сліду). */
    fun forget(id: String, why: String = "false", device: String = this.device): Boolean = store.batch {
        val it = store.get(id) ?: return@batch false
        if (it.tombstone) return@batch false
        it.status = "tombstone"
        it.reason = if (why in MemoryItem.REASONS) why else "false"
        it.validTo = it.validTo ?: now()
        it.provenance.device = device
        store.save(it)
        true
    }

    fun confirm(id: String): MemoryItem? = store.batch {
        val it = store.get(id) ?: return@batch null
        if (!it.isLive) return@batch null
        val t = now()
        if (it.predicate in MemoryItem.SINGLE_VALUED && it.value.isNotEmpty()) {
            for (r in store.liveBySlot(it.subject, it.predicate)) {
                if (r.id != it.id && r.status == "active") {
                    r.validTo = t; r.status = "superseded"; r.supersededBy = it.id
                    store.save(r)
                }
            }
        }
        it.status = "active"; it.provenance.source = "owner_said"; it.confidence = 1.0; it.lastConfirmed = t; it.legacy = false
        store.save(it)
    }

    /**
     * Прямий запис без моделі — правило походження як на ПК (`remember_text`):
     * чуже приватне повідомлення — лише з явним «запамʼятай це».
     */
    fun rememberText(
        text: String,
        origin: Origin,
        explicit: Boolean = false,
        kind: String = "fact",
        subject: String = MemoryItem.OWNER,
        predicate: String = "about",
        value: String = "",
        conversationId: String? = null,
        source: String? = null,
        confidence: Double? = null,
    ): Pair<Outcome, String> {
        if (!writeAllowed(origin, explicit)) throw WriteRefused(origin)
        val clean = text.trim().cpTake(1200)
        require(clean.isNotEmpty()) { "empty" }
        val src = source ?: if (origin == Origin.MISSION && !explicit) "heard" else "owner_said"
        val c = Candidate(
            kind = kind, subject = subject, predicate = predicate, value = value, text = clean, source = src,
            confidence = confidence ?: if (src == "owner_said") 1.0 else 0.8,
        )
        return applyCandidate(c, Provenance(source = src, conversationId = conversationId, ts = now(), device = device))
    }

    fun known(): List<MemoryItem> =
        store.list().filter { it.validTo == null }.sortedWith(compareBy<MemoryItem> { it.status != "active" }.thenByDescending { it.lastConfirmed })

    fun promptItems(): List<MemoryItem> =
        store.list(listOf("active")).filter { it.validTo == null && it.sensitivity != "secret" }

    // ══ читання ══════════════════════════════════════════════════════════
    fun vector(it: MemoryItem): DoubleArray {
        vectors[it.id]?.let { (seq, v) -> if (seq == it.seq) return v }
        val v = Text.embed("${it.displayText()} ${it.subject} ${it.predicate.replace('_', ' ')} ${it.value}")
        vectors[it.id] = it.seq to v
        return v
    }

    /** `VectorIndex.rank`: косинус + 0.5 за предикат-підказку; стабільно за спаданням. */
    fun rank(query: String, items: List<MemoryItem>): List<Pair<Double, MemoryItem>> {
        val q = Text.embed(query)
        val hinted = Text.hintedPredicates(query)
        return items.map { (Text.cosine(q, vector(it)) + if (it.predicate in hinted) 0.5 else 0.0) to it }
            .sortedByDescending { it.first }
    }

    fun contextForPrompt(query: String, budgetTokens: Int = BUDGET_TOKENS): Context {
        val live = promptItems()
        if (live.isEmpty()) {
            val text = "$HEADER\n(про власника в памʼяті поки нічого немає)\n$RULE"
            return Context(text, emptyList(), Text.estimateTokens(text))
        }
        var used = Text.estimateTokens(HEADER) + Text.estimateTokens(RULE) + 12
        val chosen = ArrayList<String>()
        val sections = ArrayList<Pair<String, List<String>>>()

        fun take(items: List<MemoryItem>, limit: Int, fmt: (MemoryItem) -> String): List<String> {
            val lines = ArrayList<String>()
            for (it in items) {
                if (it.id in chosen) continue
                val line = fmt(it)
                val cost = Text.estimateTokens(line)
                if (used + cost > limit) continue
                used += cost
                chosen += it.id
                lines += line
            }
            return lines
        }
        val fmtFact: (MemoryItem) -> String = { "- [m:${it.shortId}] ${it.displayText()}${if (it.source == "inferred") " (здогад)" else ""}" }
        val fmtEpisode: (MemoryItem) -> String = { "- [m:${it.shortId}] (${DAY.format(Instant.ofEpochMilli(it.validFrom))}) ${it.displayText()}" }

        val profile = live.filter {
            it.subject == MemoryItem.OWNER && it.predicate in MemoryItem.PROFILE_PREDICATES &&
                it.kind in listOf("fact", "person", "place") && it.source == "owner_said"
        }
        take(profile, (budgetTokens * PROFILE_SHARE).toInt(), fmtFact).takeIf { it.isNotEmpty() }?.let { sections += "Профіль:" to it }

        val rest = live.filter { it.kind != "episode" && it.id !in chosen }
        val ranked = rank(query, rest).take(SEMANTIC_TOP_K).map { it.second }
        take(ranked, budgetTokens - 150, fmtFact).takeIf { it.isNotEmpty() }?.let { sections += "Доречне до питання:" to it }

        val eps = live.filter { it.kind == "episode" }
        val epsRel = rank(query, eps).filter { it.first >= SEMANTIC_MIN }.map { it.second }.take(3)
        val epsRecent = eps.sortedByDescending { it.validFrom }.take(RECENT_EPISODES)
        take(epsRel + epsRecent, budgetTokens, fmtEpisode).takeIf { it.isNotEmpty() }?.let { sections += "Події:" to it }

        if (sections.isEmpty()) sections += "" to listOf("(доречних записів немає)")
        val body = sections.joinToString("\n") { (title, ls) -> (if (title.isNotEmpty()) "$title\n" else "") + ls.joinToString("\n") }
        val text = "$HEADER\n$body\n$RULE"
        return Context(text, chosen, Text.estimateTokens(text))
    }

    fun grounding(query: String): Grounding.Result = Grounding.ground(this, query, promptItems())

    /** «Не знаю» без моделі, коли питання про власника, а опори немає; інакше null. */
    fun ungroundedAnswer(query: String): String? =
        runCatching { if (grounding(query).ungrounded) Grounding.DONT_KNOW else null }.getOrNull()

    /** Мітки [m:xxxxxxxx] у відповіді → записи. */
    fun citations(answer: String): List<MemoryItem> {
        val shorts = CITE.findAll(answer).map { it.groupValues[1] }.distinct().toList()
        if (shorts.isEmpty()) return emptyList()
        val byShort = store.list(null).associateBy { it.shortId }
        return shorts.mapNotNull { byShort[it]?.takeIf { i -> !i.tombstone } }
    }

    fun export(): JsonObject = buildJsonObject {
        val items = store.list(listOf("active", "pending", "superseded"))
        put("format", "phantom-memory/1")
        put("exported_at", now())
        put("count", items.size)
        put("items", buildJsonArray { items.forEach { add(it.toWire()) } })
    }

    // ══ синхронізація (SYNC.md) ══════════════════════════════════════════
    data class Page(val cursor: Long, val more: Boolean, val changes: List<JsonObject>)

    fun changes(since: Long, limit: Int = 500, filter: (MemoryItem) -> Boolean = { true }): Page {
        val items = store.changesSince(since, limit + 1)
        val more = items.size > limit
        val page = items.take(limit)
        val out = page.filter(filter).map { it ->
            buildJsonObject {
                put("op", if (it.tombstone) "tombstone" else "upsert")
                put("id", it.id)
                put("lamport", it.lamport)
                put("ts", it.updatedTs)
                put("device", it.provenance.device)
                if (it.tombstone) put("why", it.reason ?: "false") else put("item", it.toWire())
            }
        }
        val cursor = page.lastOrNull()?.seq ?: maxOf(since, 0)
        return Page(cursor, more, out)
    }

    data class Applied(val applied: Int, val ignored: Int, val cursor: Long)

    fun applyChanges(changes: List<JsonElement>, device: String): Applied = store.batch {
        var applied = 0
        var ignored = 0
        for (el in changes) {
            val ch = el as? JsonObject
            val op = ch.str("op")
            val id = ch.str("id")
            if (ch == null || op !in listOf("upsert", "tombstone") || id.isNullOrEmpty()) { ignored++; continue }
            val lamport = ch.long("lamport") ?: 0
            store.observeLamport(lamport) // годинник бачить і відхилені зміни
            val srcDev = (ch.str("device")?.takeIf { it.isNotEmpty() } ?: device).cpTake(64)
            val local = store.get(id)
            if (local != null && local.tombstone) { ignored++; continue } // надгробок липкий
            if (op == "tombstone") {
                val it = local ?: MemoryItem.fromWire(JsonObject((ch["item"] as? JsonObject ?: JsonObject(emptyMap())) + ("id" to JsonPrimitive(id))), now())
                it.status = "tombstone"
                it.reason = ch.str("why")?.takeIf { w -> w in MemoryItem.REASONS } ?: "false"
                it.validTo = it.validTo ?: now()
                it.lamport = maxOf(lamport, local?.let { l -> l.lamport + 1 } ?: 0)
                it.provenance.device = srcDev
                store.save(it, keepLamport = true)
                applied++
                continue
            }
            val data = ch["item"] as? JsonObject
            if (data == null) { ignored++; continue }
            if (local != null && compareLww(local.lamport, local.provenance.device, lamport, srcDev) >= 0) { ignored++; continue }
            val it = MemoryItem.fromWire(JsonObject(data + ("id" to JsonPrimitive(id))), now())
            if (!it.tombstone && store.isTombstoned(it.displayText(), it.subject, it.predicate, it.value)) { ignored++; continue }
            it.lamport = lamport
            it.provenance.device = srcDev
            if (local != null) it.legacy = local.legacy
            store.save(it, keepLamport = true)
            applied++
            reconcileSlot(it)
        }
        Applied(applied, ignored, store.cursor())
    }

    /** `service.py::reconcile_slot` — новіша дійсність перемагає, сказане власником сильніше. */
    fun reconcileSlot(it: MemoryItem): List<String> {
        if (it.tombstone || it.status != "active" || it.value.isEmpty() || !MemoryItem.exclusive(it.predicate)) return emptyList()
        val rivals = store.liveBySlot(it.subject, it.predicate).filter { r -> r.status == "active" }
        if (rivals.size < 2) return emptyList()
        val winner = rivals.maxWith(compareBy<MemoryItem>({ r -> rank(r.source) }, { r -> r.validFrom }, { r -> r.id }))
        val changed = ArrayList<String>()
        for (r in rivals) {
            if (r.id == winner.id) continue
            if (r.validFrom > winner.validFrom) {
                r.status = "pending"
            } else {
                r.status = "superseded"; r.validTo = winner.validFrom; r.supersededBy = winner.id
            }
            r.provenance.device = device
            store.save(r)
            changed += r.id
        }
        return changed
    }

    companion object {
        const val PENDING_BELOW = 0.6
        const val BUDGET_TOKENS = 1500
        const val PROFILE_SHARE = 0.4
        const val SEMANTIC_TOP_K = 8
        const val SEMANTIC_MIN = 0.12
        const val RECENT_EPISODES = 5

        const val HEADER = "[ПАМʼЯТЬ PHANTOM — записи про власника; кожен має мітку [m:id]]"
        const val RULE = "ПРАВИЛО ПАМʼЯТІ: про власника, його людей, місця й події кажи лише те, що є в записах вище. " +
            "Якщо в памʼяті немає опори для відповіді — прямо скажи, що не знаєш (або що він тобі цього не казав), " +
            "і НЕ вигадуй та не вгадуй. Записи можуть бути застарілими — якщо власник каже інакше, вір йому."

        val CITE = Regex("\\[m:([0-9a-f]{8})]")
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

        fun rank(source: String): Int = MemoryItem.SOURCE_RANK[source] ?: 0

        fun writeAllowed(origin: Origin, explicit: Boolean): Boolean = origin != Origin.PEER_MESSAGE || explicit

        /** Python-порівняння кортежів `(lamport, device)`. */
        fun compareLww(aL: Long, aD: String, bL: Long, bD: String): Int =
            if (aL != bL) aL.compareTo(bL) else aD.compareTo(bD)

        /** Прибрати мітки з тексту для людини. */
        fun stripCitations(text: String): String = text.replace(Regex("\\s?\\[m:[0-9a-f]{8}]"), "")
    }
}
