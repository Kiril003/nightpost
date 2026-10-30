package local.phantom.companion.core.memory

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Сховище репліки — те саме, що `store.py` ПК: кожна зміна дістає `seq` (курсор
 * журналу) і `lamport` (годинник конфліктів); надгробок стирає зміст і лишає
 * відбитки (або не лишає — для «забудь»); пошук дублів і надгробків іде за
 * відбитками HMAC, не за відкритим текстом.
 *
 * У RAM — розшифровані записи (як ПК розшифровує на читанні); на диск
 * ([file]) — лише шифротекст змісту + службові колонки. Файл переписується
 * атомарно після кожної зміни або один раз наприкінці [batch].
 */
class LedgerStore(
    private val seal: Seal,
    private val file: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Row(val item: MemoryItem, val fpText: String?, val fpTriple: String?, val fpSlot: String?, var sealed: String?)

    private val lock = Any()
    private val rows = LinkedHashMap<String, Row>()
    private val meta = HashMap<String, String>()
    private var batchDepth = 0
    private var dirty = false

    init {
        file?.takeIf { it.exists() }?.let { load(it) }
    }

    // ── відбитки (store.py) ────────────────────────────────────────────────
    fun fpText(text: String): String = seal.fingerprint("t", Text.normalize(text))

    fun fpTriple(subject: String, predicate: String, value: String): String? {
        val v = Text.normalize(value)
        return if (v.isEmpty()) null else seal.fingerprint("v", subject, predicate, v)
    }

    fun fpSlot(subject: String, predicate: String): String = seal.fingerprint("s", subject, predicate)

    // ── meta / годинники ───────────────────────────────────────────────────
    fun metaGet(key: String): String? = synchronized(lock) { meta[key] }

    fun metaSet(key: String, value: Any) = synchronized(lock) {
        meta[key] = value.toString()
        touched()
    }

    fun metaLong(key: String): Long = metaGet(key)?.toLongOrNull() ?: 0

    private fun next(key: String): Long {
        val n = metaLong(key) + 1
        meta[key] = n.toString()
        return n
    }

    fun observeLamport(remote: Long) = synchronized(lock) {
        if (remote > metaLong("lamport")) { meta["lamport"] = remote.toString(); touched() }
    }

    fun cursor(): Long = metaLong("seq")

    // ── запис ──────────────────────────────────────────────────────────────
    fun save(item: MemoryItem, keepLamport: Boolean = false): MemoryItem = synchronized(lock) {
        if (keepLamport) {
            if (item.lamport > metaLong("lamport")) meta["lamport"] = item.lamport.toString()
        } else {
            item.lamport = next("lamport")
        }
        item.seq = next("seq")
        item.updatedTs = now()
        write(item)
        touched()
        item
    }

    private fun write(item: MemoryItem) {
        val existing = rows[item.id]
        var triple = fpTriple(item.subject, item.predicate, item.value)
        val ftext: String?
        val fslot: String?
        if (item.tombstone && item.reason == "forget") {
            ftext = null; triple = null; fslot = null
        } else if (item.tombstone && existing != null) {
            ftext = existing.fpText
            triple = existing.fpTriple ?: triple
            fslot = existing.fpSlot
        } else {
            ftext = if (item.displayText().isNotEmpty()) fpText(item.displayText()) else null
            fslot = fpSlot(item.subject, item.predicate)
        }
        val stored = item.deepCopy()
        if (stored.tombstone) {
            // «неправда»/«забудь» стирає зміст; лишається лише службове
            stored.subject = ""; stored.predicate = ""; stored.value = ""; stored.text = ""
            stored.provenance.conversationId = null; stored.provenance.messageId = null; stored.provenance.ts = 0
            stored.reason = stored.reason ?: "false"
        } else {
            stored.reason = null
        }
        rows.remove(item.id) // як INSERT OR REPLACE: рядок стає «новим»
        rows[item.id] = Row(stored, ftext, triple, fslot, sealed = null)
    }

    // ── читання ────────────────────────────────────────────────────────────
    fun get(id: String): MemoryItem? = synchronized(lock) { rows[id]?.item?.deepCopy() }

    /** `ORDER BY last_confirmed DESC`; рівні — у порядку запису. */
    fun list(statuses: Collection<String>? = MemoryItem.LIVE_STATUSES): List<MemoryItem> = synchronized(lock) {
        rows.values.map { it.item }
            .filter { statuses == null || it.status in statuses }
            .sortedByDescending { it.lastConfirmed }
            .map { it.deepCopy() }
    }

    private fun live(pred: (Row) -> Boolean, needOpen: Boolean = true): List<MemoryItem> = synchronized(lock) {
        rows.values.filter { r ->
            r.item.status in MemoryItem.LIVE_STATUSES && (!needOpen || r.item.validTo == null) && pred(r)
        }.map { it.item.deepCopy() }
    }

    fun liveBySlot(subject: String, predicate: String): List<MemoryItem> {
        val s = fpSlot(subject, predicate)
        return live({ it.fpSlot == s })
    }

    fun liveByTriple(subject: String, predicate: String, value: String): List<MemoryItem> {
        val t = fpTriple(subject, predicate, value) ?: return emptyList()
        return live({ it.fpTriple == t })
    }

    fun liveByText(text: String): List<MemoryItem> {
        val t = fpText(text)
        return live({ it.fpText == t }, needOpen = false)
    }

    fun isTombstoned(text: String?, subject: String, predicate: String, value: String): Boolean {
        val ft = text?.takeIf { it.isNotEmpty() }?.let { fpText(it) }
        val tr = fpTriple(subject, predicate, value)
        if (ft == null && tr == null) return false
        return synchronized(lock) {
            rows.values.any { r ->
                r.item.status == "tombstone" && ((ft != null && r.fpText == ft) || (tr != null && r.fpTriple == tr))
            }
        }
    }

    fun changesSince(since: Long, limit: Int): List<MemoryItem> = synchronized(lock) {
        rows.values.map { it.item }.filter { it.seq > since }.sortedBy { it.seq }.take(limit).map { it.deepCopy() }
    }

    fun size(): Int = synchronized(lock) { rows.size }

    // ── пакет і диск ───────────────────────────────────────────────────────
    fun <T> batch(block: () -> T): T {
        synchronized(lock) { batchDepth++ }
        try {
            return block()
        } finally {
            synchronized(lock) {
                batchDepth--
                if (batchDepth == 0 && dirty) flush()
            }
        }
    }

    private fun touched() {
        dirty = true
        if (batchDepth == 0) flush()
    }

    private fun flush() {
        val f = file ?: run { dirty = false; return }
        val doc = buildJsonObject {
            put("v", 1)
            put("meta", buildJsonObject { meta.forEach { (k, v) -> put(k, v) } })
            put("rows", buildJsonArray {
                for (r in rows.values) {
                    val it = r.item
                    if (r.sealed == null && !it.tombstone) r.sealed = seal.seal(Seal.contentPayload(it), aad = it.id)
                    add(buildJsonObject {
                        put("id", it.id); put("kind", it.kind); put("status", it.status); put("source", it.source)
                        put("sealed", r.sealed); put("fp_text", r.fpText); put("fp_triple", r.fpTriple); put("fp_slot", r.fpSlot)
                        put("confidence", it.confidence); put("sensitivity", it.sensitivity)
                        put("valid_from", it.validFrom); put("valid_to", it.validTo); put("last_confirmed", it.lastConfirmed)
                        put("superseded_by", it.supersededBy); put("legacy", it.legacy); put("lamport", it.lamport)
                        put("seq", it.seq); put("device", it.provenance.device); put("updated_ts", it.updatedTs)
                        put("reason", it.reason)
                    })
                }
            })
        }
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(doc.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        dirty = false
    }

    private fun load(f: File) {
        val doc = runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: return
        (doc["meta"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.let { meta[k] = it } }
        for (el in (doc["rows"] as? JsonArray) ?: JsonArray(emptyList())) {
            val r = el as? JsonObject ?: continue
            val id = r.str("id") ?: continue
            val sealedTok = r.str("sealed")
            val content = sealedTok?.let { runCatching { seal.open(it, id) }.getOrNull() }
            if (sealedTok != null && content == null) continue // чужий ключ чи пошкоджено — не вигадуємо
            val prov = content?.get("provenance") as? JsonObject
            val item = MemoryItem(
                id = id, kind = r.str("kind") ?: "fact",
                subject = content.str("subject") ?: "", predicate = content.str("predicate") ?: "",
                value = content.str("value") ?: "", text = content.str("text") ?: "",
                provenance = Provenance(
                    source = r.str("source") ?: "inferred", conversationId = prov.str("conversation_id"),
                    messageId = prov.str("message_id"), ts = prov.long("ts") ?: 0, device = r.str("device") ?: "phone",
                ),
                confidence = r.dbl("confidence") ?: 0.0, status = r.str("status") ?: "active",
                validFrom = r.long("valid_from") ?: 0, validTo = r.long("valid_to"),
                lastConfirmed = r.long("last_confirmed") ?: 0, sensitivity = r.str("sensitivity") ?: "normal",
                supersededBy = r.str("superseded_by"), legacy = r.bool("legacy"), lamport = r.long("lamport") ?: 0,
                seq = r.long("seq") ?: 0, updatedTs = r.long("updated_ts") ?: 0, reason = r.str("reason"),
            )
            rows[id] = Row(item, r.str("fp_text"), r.str("fp_triple"), r.str("fp_slot"), sealedTok)
        }
    }

    /** Для тестів шифрування: що лежить на диску. */
    fun rawFile(): String? = file?.takeIf { it.exists() }?.readText()
}
