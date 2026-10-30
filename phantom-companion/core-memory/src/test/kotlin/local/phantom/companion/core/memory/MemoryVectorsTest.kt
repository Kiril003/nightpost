package local.phantom.companion.core.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Телефон рахує те саме, що ПК. Вектори пише Python —
 * `phantom-os/src/backend/tests/memory_vectors.py` — з ПОТОЧНОГО коду ledger-а ПК;
 * `test_memory_vectors.py` стереже, що ця копія з ним збігається.
 */
class MemoryVectorsTest {

    private val v: JsonObject by lazy {
        val raw = checkNotNull(javaClass.classLoader?.getResourceAsStream("memory_vectors.json")) { "memory_vectors.json" }
        Json.parseToJsonElement(raw.reader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun JsonObject.arr(k: String): List<JsonObject> = this[k]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.strs(k: String): List<String> = this[k]!!.jsonArray.map { it.jsonPrimitive.content }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Запис рівно таким, яким його збудував Python (без нормалізації `from_wire`). */
    private fun raw(w: JsonObject) = MemoryItem(
        id = w.s("id")!!, kind = w.s("kind")!!, subject = w.s("subject")!!, predicate = w.s("predicate")!!,
        value = w.s("value")!!, text = w.s("text")!!, provenance = Provenance.fromWire(w["provenance"]!!.jsonObject),
        confidence = w["confidence"]!!.jsonPrimitive.double, status = w.s("status")!!,
        validFrom = w["valid_from"]!!.jsonPrimitive.long, validTo = (w["valid_to"] as? JsonPrimitive)?.contentOrNull?.toLong(),
        lastConfirmed = w["last_confirmed"]!!.jsonPrimitive.long, sensitivity = w.s("sensitivity")!!,
        supersededBy = w.s("superseded_by"),
    )

    /** Рівність JSON за змістом: числа — як числа (1 == 1.0), обʼєкти — без порядку ключів. */
    private fun same(a: JsonElement?, b: JsonElement?): Boolean = when {
        a == null || a is JsonNull -> b == null || b is JsonNull
        b == null || b is JsonNull -> false
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a[it], b[it]) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive -> when {
            a.isString || b.isString -> a.isString == b.isString && a.content == b.content
            a.booleanOrNull != null || b.booleanOrNull != null -> a.booleanOrNull == b.booleanOrNull
            else -> a.doubleOrNull == b.doubleOrNull
        }
        else -> false
    }

    @Test fun `normalize, terms, hints, tokens and the hashed vector match the PC bit for bit`() {
        val rows = v["text"]!!.jsonObject.arr("rows")
        assertTrue(rows.size >= 15)
        for (r in rows) {
            val input = r.s("in")!!
            assertEquals(input, r.s("normalize"), Text.normalize(input))
            r.s("predicate")?.let { assertEquals(input, it, Text.normalizePredicate(input)) }
            r.s("subject")?.let { assertEquals(input, it, Text.normalizeSubject(input)) }
            assertEquals(input, r.strs("terms"), Text.terms(input))
            assertEquals(input, r.strs("hints"), Text.hintedPredicates(input).sorted())
            assertEquals(input, r["tokens"]!!.jsonPrimitive.int, Text.estimateTokens(input))
            val want = r["embed"]!!.jsonObject.mapKeys { it.key.toInt() }.mapValues { it.value.jsonPrimitive.double }
            val got = Text.embed(input)
            assertEquals("$input: ненульові виміри", want.keys, got.indices.filter { got[it] != 0.0 }.toSet())
            for ((i, x) in want) assertEquals("$input[$i]", x, got[i], 1e-9)
        }
    }

    @Test fun `keys, seal and fingerprints are the PC's — the phone opens a PC record and seals it identically`() {
        val c = v["crypto"]!!.jsonObject
        val seal = Seal.fromSecret(c.s("secret")!!)
        assertEquals(c.s("data_key"), with(Seal) { seal.dataKey.toHex() })
        assertEquals(c.s("fp_key"), with(Seal) { seal.fpKey.toHex() })
        for (s in c.arr("seal")) {
            val aad = s.s("aad")!!
            val p = s["payload"]!!.jsonObject
            val prov = p["provenance"]!!.jsonObject
            val item = MemoryItem(
                id = aad, subject = p.s("subject")!!, predicate = p.s("predicate")!!, value = p.s("value")!!,
                text = p.s("text")!!,
                provenance = Provenance(conversationId = prov.s("conversation_id"), messageId = prov.s("message_id"),
                    ts = prov["ts"]!!.jsonPrimitive.long),
            )
            assertEquals(s.s("token"), seal.seal(Seal.contentPayload(item), aad, hex(s.s("nonce")!!)))
            assertTrue(same(p, seal.open(s.s("token")!!, aad)))
        }
        for (f in c.arr("fingerprint")) assertEquals(f.s("fp"), seal.fingerprint(*f.strs("parts").toTypedArray()))
    }

    @Test fun `the record format on the wire is the PC's in both directions`() {
        val w = v["wire"]!!.jsonObject
        for (row in w.arr("to_wire")) {
            val wire = row["wire"]!!.jsonObject
            val item = raw(wire)
            assertTrue("${row.s("item_id")}: ${item.toWire()}", same(wire, item.toWire()))
        }
        for (row in w.arr("from_wire")) {
            val got = MemoryItem.fromWire(row["in"]!!.jsonObject, nowMs = -1).toWire()
            assertTrue("$got\n≠\n${row["wire"]}", same(row["wire"], got))
        }
    }

    private fun ledgerWithCorpus(): Ledger {
        val ledger = Ledger(LedgerStore(Seal.fromSecret("phone-test")), device = "pc-vec")
        for (w in v["context"]!!.jsonObject.arr("corpus")) ledger.store.save(raw(w), keepLamport = true)
        return ledger
    }

    @Test fun `the same memory goes into the prompt under the same 1500-token budget`() {
        val ctx = v["context"]!!.jsonObject
        val ledger = ledgerWithCorpus()
        assertEquals(1500, v["budget_tokens"]!!.jsonPrimitive.int)
        for (c in ctx.arr("cases")) {
            val budget = c["budget"]?.jsonPrimitive?.int ?: Ledger.BUDGET_TOKENS
            val got = ledger.contextForPrompt(c.s("query")!!, budget)
            assertEquals(c.s("query"), c.s("text"), got.text)
            assertEquals(c.s("query"), c.strs("item_ids"), got.itemIds)
            assertEquals(c.s("query"), c["tokens"]!!.jsonPrimitive.int, got.tokens)
            assertTrue(got.tokens <= budget)
        }
        val empty = Ledger(LedgerStore(Seal.fromSecret("x"))).contextForPrompt("Як мене звати?")
        assertEquals(ctx["empty"]!!.jsonObject.s("text"), empty.text)
    }

    @Test fun `no grounding means the same «не знаю» on both brains`() {
        val ledger = ledgerWithCorpus()
        val live = ledger.promptItems()
        val rows = v["grounding"]!!.jsonObject.arr("rows")
        assertTrue(rows.count { it["ungrounded"]!!.jsonPrimitive.boolean } >= 5)
        for (r in rows) {
            val q = r.s("query")!!
            val g = Grounding.ground(ledger, q, live)
            assertEquals(q, r["owner_question"]!!.jsonPrimitive.boolean, g.askedAboutOwner)
            assertEquals(q, r["ungrounded"]!!.jsonPrimitive.boolean, g.ungrounded)
            assertEquals(q, r["best"]!!.jsonPrimitive.double, g.bestScore, 1e-4)
            assertEquals(q, r.strs("items"), g.items.map { it.id })
        }
    }

    private fun cand(o: JsonObject) = Candidate(
        kind = o.s("kind")!!, subject = o.s("subject")!!, predicate = o.s("predicate")!!, value = o.s("value")!!,
        text = o.s("text")!!, source = o.s("source")!!, confidence = o["confidence"]!!.jsonPrimitive.double,
        sensitivity = o.s("sensitivity")!!,
    )

    @Test fun `write rules — pending, confirm, supersede, owner beats heard, untrue blocks, forget does not`() {
        for (sc in v["write"]!!.jsonObject.arr("scenarios")) {
            val ledger = Ledger(LedgerStore(Seal.fromSecret("phone-test")), device = "pc-vec")
            val outcomes = ArrayList<String>()
            var last = ""
            for (step in sc["steps"]!!.jsonArray.map { it.jsonObject }) {
                when {
                    "add" in step -> {
                        val c = cand(step["add"]!!.jsonObject)
                        val (o, id) = ledger.applyCandidate(c, Provenance(source = c.source, conversationId = "vec", device = "pc-vec"))
                        outcomes += o.wire
                        if (id.isNotEmpty()) last = id
                    }
                    "retract" in step -> outcomes += "retracted:${ledger.retract(cand(step["retract"]!!.jsonObject)).size}"
                    else -> outcomes += "forgot:${if (ledger.forget(last, why = step.s("forget_last")!!)) "True" else "False"}"
                }
            }
            val name = sc.s("name")
            assertEquals(name, sc.strs("outcomes"), outcomes)
            val items = ledger.store.list(null).filter { it.status != "tombstone" }
                .map { listOf(it.status, it.predicate, it.value, it.source) }
                .sortedWith { a, b -> a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0 }
            assertEquals(name, sc["items"]!!.jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.content } }, items)
            assertEquals(name, sc.strs("tombstones"), ledger.store.list(listOf("tombstone")).map { it.reason ?: "" }.sorted())
        }
    }

    @Test fun `sync ops — LWW, device tie-break, sticky tombstone, why, newer validity wins`() {
        for (sc in v["sync"]!!.jsonObject.arr("scenarios")) {
            val name = sc.s("name")
            val ledger = Ledger(LedgerStore(Seal.fromSecret("phone-test")), device = "pc-vec")
            for (row in sc.arr("local")) {
                val it = MemoryItem.fromWire(row["item"]!!.jsonObject, 0)
                it.lamport = row["lamport"]!!.jsonPrimitive.long
                it.provenance.device = row.s("device")!!
                ledger.store.save(it, keepLamport = true)
                row.s("tombstone")?.let { why -> ledger.forget(it.id, why = why, device = row.s("device")!!) }
            }
            val before = ledger.store.cursor()
            val res = ledger.applyChanges(sc["changes"]!!.jsonArray, device = "phone-1")
            val want = sc["result"]!!.jsonObject
            assertEquals("$name applied", want["applied"]!!.jsonPrimitive.int, res.applied)
            assertEquals("$name ignored", want["ignored"]!!.jsonPrimitive.int, res.ignored)
            assertEquals("$name lamport", sc["max_lamport"]!!.jsonPrimitive.long, ledger.store.metaLong("lamport"))

            val state = sc["state"]!!.jsonObject
            val have = ledger.store.list(null).associateBy { it.id }
            assertEquals(name, state.keys, have.keys)
            for ((id, exp) in state) {
                val e = exp.jsonObject
                val it = have.getValue(id)
                val tag = "$name/$id"
                assertEquals(tag, e.s("status"), it.status)
                assertEquals(tag, e.s("value"), it.value)
                assertEquals(tag, e.s("text"), it.text)
                assertEquals(tag, e.s("source"), it.source)
                assertEquals(tag, e["valid_to_set"]!!.jsonPrimitive.boolean, it.validTo != null)
                assertEquals(tag, e.s("superseded_by"), it.supersededBy)
                assertEquals(tag, e.s("reason"), it.reason)
                assertEquals(tag, e["lamport"]!!.jsonPrimitive.long, it.lamport)
                assertEquals(tag, e.s("device"), it.provenance.device)
                assertEquals(tag, e["confidence"]!!.jsonPrimitive.double, MemoryItem.round3(it.confidence), 1e-9)
            }
            val feed = ledger.changes(before, 500).changes.map { ch ->
                listOf(ch.s("op"), ch.s("id"), ch["lamport"]!!.jsonPrimitive.long.toString(), ch.s("device"), ch.s("why"))
            }
            val wantFeed = sc.arr("feed").map { f ->
                listOf(f.s("op"), f.s("id"), f["lamport"]!!.jsonPrimitive.long.toString(), f.s("device"), f.s("why"))
            }
            assertEquals(name, wantFeed, feed)
        }
    }
}
