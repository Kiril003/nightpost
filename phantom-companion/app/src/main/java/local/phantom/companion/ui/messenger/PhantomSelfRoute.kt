package local.phantom.companion.ui.messenger

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.memory.Ledger
import local.phantom.companion.self.PhantomSelf
import local.phantom.companion.self.SelfMemory

/**
 * `/messenger/self/facts` — «що я про тебе знаю» для веб-розмови з PHANTOM:
 * список і «неправда» біля кожного рядка.
 */
class PhantomSelfRoute(
    private val self: () -> PhantomSelf,
    private val label: (SelfMemory.From) -> String,
    private val ask: suspend (prompt: String, system: String?) -> String? = { _, _ -> null },
    private val noMind: () -> String = { "" },
    private val takeOpen: () -> String? = { null },
    private val voice: (Boolean?) -> Pair<Boolean, String> = { false to "" },
    /** Чесний стан памʼяті й синку (MemoryRuntime.state) або null — памʼять лише локальна. */
    private val memoryState: () -> JsonObject? = { null },
    /** Перевірка хоста: чи належить `message_id` у цій розмові самому власнику. */
    private val isOwnerMessage: (chatId: String, messageId: String) -> Boolean = { _, _ -> false },
) {

    fun claims(seg: List<String>): Boolean = seg.size >= 3 && seg[1] == "self" &&
        (seg[2] == "facts" || seg[2] == "ask" || seg[2] == "open" || seg[2] == "voice" || seg[2] == "memory" || seg[2] == "commitments")

    suspend fun handle(method: String, seg: List<String>, body: String? = null): String = runCatching {
        run {
            when {
                seg.size == 3 && seg[1] == "self" && seg[2] == "ask" && method == "POST" -> once(body)
                seg.size == 3 && seg[1] == "self" && seg[2] == "voice" && method == "GET" -> voiceState(null)
                seg.size == 3 && seg[1] == "self" && seg[2] == "voice" && method == "POST" -> {
                    val on = runCatching { Json.parseToJsonElement(body ?: "").jsonObject["on"] as? JsonPrimitive }
                        .getOrNull()?.content?.toBooleanStrictOrNull()
                    voiceState(on ?: false)
                }
                seg.size == 3 && seg[1] == "self" && seg[2] == "open" && method == "GET" ->
                    ok(buildJsonObject { put("chat_id", takeOpen()) })
                seg.size == 3 && seg[1] == "self" && seg[2] == "facts" && method == "GET" -> ok(list())
                seg.size == 3 && seg[1] == "self" && seg[2] == "facts" && method == "POST" -> remember(body)
                seg.size == 4 && seg[1] == "self" && seg[2] == "facts" && seg[3] == "export" && method == "GET" ->
                    ok(self().memory.ledger.export())
                seg.size == 3 && seg[1] == "self" && seg[2] == "memory" && method == "GET" -> ok(state())
                seg.size == 5 && seg[1] == "self" && seg[2] == "facts" && seg[4] == "forget" && method == "POST" -> {
                    // «неправда» (типово, як і досі) чи «забудь» — `{"why": "false" | "forget"}`
                    val why = runCatching { Json.parseToJsonElement(body ?: "").jsonObject.str("why") }.getOrNull()
                        ?.takeIf { it == "forget" } ?: "false"
                    val gone = self().memory.forget(seg[3], why)
                    if (gone) ok(buildJsonObject { put("forgotten", true) }) else fail(404, "gone")
                }
                seg.size == 3 && seg[1] == "self" && seg[2] == "commitments" && method == "GET" ->
                    ok(commitmentsList())
                seg.size == 3 && seg[1] == "self" && seg[2] == "commitments" && method == "POST" ->
                    chooseCommitment(body)
                seg.size == 5 && seg[1] == "self" && seg[2] == "commitments" && seg[4] == "done" && method == "POST" ->
                    markCommitment(seg[3], done = true)
                seg.size == 5 && seg[1] == "self" && seg[2] == "commitments" && seg[4] == "drop" && method == "POST" ->
                    markCommitment(seg[3], done = false)
                else -> fail(404, "not_found")
            }
        }
    }.getOrElse { fail(500, "failed") }

    /**
     * `POST /messenger/self/ask` `{"text": "...", "system": "..."}` → `{"answer": "..."}`.
     * Разова відповідь розуму поза журналом розмови — для «перекласти»,
     * «підсумок» тощо. Без розуму — 503 з людським поясненням у `detail`.
     */
    private suspend fun once(body: String?): String {
        val o = runCatching { Json.parseToJsonElement(body ?: "").jsonObject }.getOrNull() ?: return fail(400, "bad_body")
        val text = o.str("text")
        if (text.isEmpty()) return fail(400, "empty")
        val system = o.str("system").ifEmpty { null }
        val answer = ask(text.take(8000), system?.take(2000)) ?: return fail(503, "no_mind", noMind())
        return ok(buildJsonObject { put("answer", answer) })
    }

    /**
     * `POST /messenger/self/facts` `{"text", "origin": "owner_statement|peer_message", "explicit", "chat_id"}` —
     * власник сам пише, що памʼятати; чуже повідомлення — ЛИШЕ з явним «запамʼятай це».
     */
    private suspend fun remember(body: String?): String {
        val o = runCatching { Json.parseToJsonElement(body ?: "").jsonObject }.getOrNull() ?: return fail(400, "bad_body")
        val text = o.str("text")
        if (text.isEmpty() || text.length > 1200) return fail(400, "empty")
        val claimedOrigin = when (o.str("origin")) {
            "", "owner_statement" -> Ledger.Origin.OWNER_STATEMENT
            "peer_message" -> Ledger.Origin.PEER_MESSAGE
            else -> return fail(400, "bad_origin")
        }
        val explicit = (o["explicit"] as? JsonPrimitive)?.content == "true"
        val chat = o.str("chat_id").ifEmpty { null }
        val messageId = o.str("message_id").ifEmpty { null }
        val hostOrigin = when {
            chat == AgencySessionManager.PHANTOM_AGENT_ID -> Ledger.Origin.OWNER_STATEMENT
            chat == null && messageId == null -> Ledger.Origin.OWNER_STATEMENT
            chat != null && messageId != null && isOwnerMessage(chat, messageId) -> Ledger.Origin.OWNER_STATEMENT
            else -> Ledger.Origin.PEER_MESSAGE
        }
        // Поле origin від клієнта може лише понизити (owner -> peer), ніколи підвищити.
        val origin = if (hostOrigin == Ledger.Origin.PEER_MESSAGE || claimedOrigin == Ledger.Origin.PEER_MESSAGE) {
            Ledger.Origin.PEER_MESSAGE
        } else {
            Ledger.Origin.OWNER_STATEMENT
        }
        val mem = self().memory
        val (outcome, id) = try {
            mem.ledger.rememberText(text, origin, explicit = explicit, conversationId = chat?.let { "chat:$it" })
        } catch (_: Ledger.WriteRefused) {
            return fail(403, "needs_explicit_remember")
        }
        if (outcome == Ledger.Outcome.BLOCKED) return fail(409, "marked_untrue")
        mem.importLegacy() // і сповістить синк
        val k = mem.known().firstOrNull { it.id == id } ?: return ok(buildJsonObject { put("id", id) })
        return ok(row(k))
    }

    private suspend fun state(): JsonElement {
        val mem = self().memory
        return memoryState() ?: buildJsonObject {
            val known = mem.known()
            put("node", "phone")
            put("items", known.count { !it.pending })
            put("pending", known.count { it.pending })
            put("budget_tokens", Ledger.BUDGET_TOKENS)
            put("persistent", false)
            put("sync", buildJsonObject { put("peer", "pc"); put("paired", false) })
        }
    }

    private fun voiceState(want: Boolean?): String {
        val (on, why) = voice(want)
        return ok(
            buildJsonObject {
                put("on", on)
                put("detail", why)
            },
        )
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

    private suspend fun list(): JsonElement = buildJsonArray {
        self().memory.known().forEach { k -> add(row(k)) }
    }

    private fun commitmentsList(): JsonElement = buildJsonArray {
        self().commitments?.listOpen().orEmpty().forEach { c -> add(commitmentRow(c)) }
    }

    private fun chooseCommitment(body: String?): String {
        val svc = self().commitments ?: return fail(503, "unavailable")
        val o = runCatching { Json.parseToJsonElement(body ?: "").jsonObject }.getOrNull()
            ?: return fail(400, "bad_body")
        val hasChoose = o["choose"] is JsonObject
        val chooseObj = (o["choose"] as? JsonObject) ?: o
        val pendingId = chooseObj.str("pending_id")
        if (hasChoose || pendingId.isNotEmpty() || (o.str("person").isEmpty() && o.str("person_name").isEmpty() && o.str("what").isEmpty())) {
            val peerId = chooseObj.str("peer_id")
            if (peerId.isEmpty()) return fail(400, "missing_peer_id")
            val saved = svc.choose(pendingId, peerId) ?: return fail(404, "not_found")
            return ok(commitmentRow(saved))
        }
        val person = o.str("person").ifEmpty { o.str("person_name") }
        val what = o.str("what")
        if (person.isEmpty() || what.isEmpty()) return fail(400, "missing_fields")
        val peerId = o.str("peer_id").ifEmpty { null }
        val text = o.str("text").ifEmpty { "$person: $what" }
        val dueMs = (o["due_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
        val sourceMessageId = o.str("source_message_id")
        val saved = svc.addDirect(
            personName = person,
            peerId = peerId,
            what = what,
            text = text,
            dueMs = dueMs,
            sourceMessageId = sourceMessageId,
        )
        return ok(commitmentRow(saved))
    }

    private fun markCommitment(id: String, done: Boolean): String {
        val svc = self().commitments ?: return fail(503, "unavailable")
        val updated = if (done) svc.done(id) else svc.drop(id)
        if (!updated) return fail(404, "not_found")
        return ok(
            buildJsonObject {
                put("id", id)
                put("state", if (done) "done" else "dropped")
            },
        )
    }

    private fun commitmentRow(c: local.phantom.companion.self.Commitment): JsonObject = buildJsonObject {
        put("id", c.id)
        put("text", c.text)
        put("person", c.personName)
        put("person_name", c.personName)
        put("peer_id", c.peerId)
        put("what", c.what)
        put("due_ms", c.dueMs)
        put("created_ms", c.createdMs)
        put("source_message_id", c.sourceMessageId)
        put("state", c.state.wire)
    }

    /** Та сама форма, що `routes_memory_self.py::_fact_out` на ПК. */
    private fun row(k: SelfMemory.Known): JsonObject = buildJsonObject {
        put("id", k.id)
        put("text", k.text)
        put("from", k.from.name.lowercase())
        put("from_label", label(k.from))
        put("skill", k.skill)
        put("old", k.old)
        put("at", Instant.ofEpochMilli(k.atMs).toString())
        put("pending", k.pending)
        put("group", k.group)
        put("subject", k.subject)
        put("source", k.source)
        put("device", k.device)
        put("confidence", Math.round(k.confidence * 100) / 100.0)
    }

    private fun ok(data: JsonElement): String = buildJsonObject {
        put("ok", true)
        put("status", 200)
        put("data", data)
    }.toString()

    private fun fail(status: Int, code: String, detail: String = ""): String = buildJsonObject {
        put("ok", false)
        put("status", status)
        put("code", code)
        put("detail", detail)
    }.toString()
}
