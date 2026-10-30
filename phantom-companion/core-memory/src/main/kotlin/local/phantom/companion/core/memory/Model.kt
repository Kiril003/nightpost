package local.phantom.companion.core.memory

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Звідки запис: хто сказав, у якій розмові, коли, на якому вузлі. */
data class Provenance(
    var source: String = "inferred",
    var conversationId: String? = null,
    var messageId: String? = null,
    var ts: Long = 0,
    var device: String = "phone",
) {
    fun toWire(): JsonObject = buildJsonObject {
        put("source", source)
        put("conversation_id", conversationId)
        put("message_id", messageId)
        put("ts", ts)
        put("device", device)
    }

    companion object {
        fun fromWire(d: JsonObject?): Provenance {
            val src = d.str("source")?.takeIf { it in MemoryItem.SOURCES } ?: "inferred"
            return Provenance(
                source = src,
                conversationId = d.str("conversation_id"),
                messageId = d.str("message_id"),
                ts = d.long("ts") ?: 0,
                device = (d.str("device")?.takeIf { it.isNotEmpty() } ?: "pc").cpTake(64),
            )
        }
    }
}

/**
 * Єдиний запис памʼяті — та сама форма, що `MemoryItem` ПК (`model.py`), і той
 * самий дріт (`to_wire`/`from_wire`). Однаковість звіряє `MemoryVectorsTest`.
 */
data class MemoryItem(
    var id: String = UUID.randomUUID().toString(),
    var kind: String = "fact",
    var subject: String = OWNER,
    var predicate: String = "about",
    var value: String = "",
    var text: String = "",
    var provenance: Provenance = Provenance(),
    var confidence: Double = 0.5,
    var status: String = "active",
    var validFrom: Long = 0,
    var validTo: Long? = null,
    var lastConfirmed: Long = 0,
    var sensitivity: String = "normal",
    var supersededBy: String? = null,
    var legacy: Boolean = false,
    var lamport: Long = 0,
    var seq: Long = 0,
    var updatedTs: Long = 0,
    /** Для надгробка: "false" («неправда») чи "forget" («забудь»). */
    var reason: String? = null,
) {
    val tombstone: Boolean get() = status == "tombstone"
    val source: String get() = provenance.source
    val isLive: Boolean get() = status in LIVE_STATUSES && validTo == null
    val shortId: String get() = id.replace("-", "").cpTake(8)

    fun displayText(): String {
        if (text.isNotEmpty()) return text
        return listOf(if (subject != OWNER) subject else "", predicate, value).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun deepCopy(): MemoryItem = copy(provenance = provenance.copy())

    fun toWire(): JsonObject = buildJsonObject {
        put("id", id)
        put("kind", kind)
        put("subject", subject)
        put("predicate", predicate)
        put("value", value)
        put("text", text)
        put("provenance", provenance.toWire())
        put("confidence", round3(confidence))
        put("status", status)
        put("valid_from", validFrom)
        put("valid_to", validTo)
        put("last_confirmed", lastConfirmed)
        put("sensitivity", sensitivity)
        put("superseded_by", supersededBy)
        put("tombstone", tombstone)
    }

    companion object {
        const val OWNER = "owner"
        val KINDS = listOf("fact", "episode", "procedure", "person", "place")
        val SOURCES = listOf("owner_said", "heard", "inferred")
        val SENSITIVITIES = listOf("normal", "personal", "secret")
        val STATUSES = listOf("active", "pending", "superseded", "merged", "faded", "tombstone")
        val LIVE_STATUSES = listOf("active", "pending")
        val REASONS = listOf("false", "forget")
        val SOURCE_RANK = mapOf("owner_said" to 3, "heard" to 2, "inferred" to 1)

        val SINGLE_VALUED = setOf(
            "name", "lives_in", "works_as", "works_at", "studies_at", "birthday",
            "age", "partner", "hometown", "native_language", "car", "phone",
            "email", "nickname", "job", "city", "relationship", "diet",
        )
        val PROFILE_PREDICATES = setOf(
            "name", "nickname", "lives_in", "hometown", "works_as", "works_at",
            "studies_at", "birthday", "age", "partner", "child", "native_language",
            "diet", "allergy",
        )

        fun exclusive(predicate: String) = predicate in SINGLE_VALUED || predicate.startsWith("favorite_")

        /** Python `round(x, 3)`: точне двійкове значення, округлення до парного. */
        fun round3(x: Double): Double = BigDecimal(x).setScale(3, RoundingMode.HALF_EVEN).toDouble()

        /** `MemoryItem.from_wire`: усе чуже нормалізується й обрізається, як на ПК. */
        fun fromWire(d: JsonObject, nowMs: Long): MemoryItem {
            val kind = d.str("kind")?.takeIf { it in KINDS } ?: "fact"
            var status = d.str("status")?.takeIf { it in STATUSES } ?: "active"
            if (d.bool("tombstone")) status = "tombstone"
            val sens = d.str("sensitivity")?.takeIf { it in SENSITIVITIES } ?: "normal"
            return MemoryItem(
                id = (d.str("id")?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()).cpTake(64),
                kind = kind,
                subject = Text.normalizeSubject(d.str("subject")),
                predicate = Text.normalizePredicate(d.str("predicate")),
                value = (d.str("value") ?: "").cpTake(500),
                text = (d.str("text") ?: "").cpTake(1200),
                provenance = Provenance.fromWire(d["provenance"] as? JsonObject),
                confidence = (d.dbl("confidence") ?: 0.0).coerceIn(0.0, 1.0),
                status = status,
                validFrom = d.long("valid_from")?.takeIf { it != 0L } ?: nowMs,
                validTo = d.long("valid_to")?.takeIf { it != 0L },
                lastConfirmed = d.long("last_confirmed")?.takeIf { it != 0L } ?: nowMs,
                sensitivity = sens,
                supersededBy = d.str("superseded_by"),
            )
        }
    }
}

/** Кандидат у памʼять — те, що дає витяг (`extract.py::Candidate`). */
data class Candidate(
    val kind: String = "fact",
    val subject: String = MemoryItem.OWNER,
    val predicate: String = "about",
    val value: String = "",
    val text: String = "",
    val source: String = "inferred",
    val confidence: Double = 0.5,
    val sensitivity: String = "normal",
)

// ── дрібниці JSON ────────────────────────────────────────────────────────────

internal fun String.cpTake(n: Int): String {
    val count = codePointCount(0, length)
    return if (count <= n) this else substring(0, offsetByCodePoints(0, n))
}

internal fun JsonObject?.prim(key: String): JsonPrimitive? = (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }

/** Рядок як Python `str(...)` у from_wire: рядок — сам, число — його запис. */
internal fun JsonObject?.str(key: String): String? = prim(key)?.contentOrNull

internal fun JsonObject?.long(key: String): Long? {
    val p = prim(key) ?: return null
    p.booleanOrNull?.let { if (!p.isString) return if (it) 1 else 0 }
    return p.content.toLongOrNull() ?: p.doubleOrNull?.toLong()
}

internal fun JsonObject?.dbl(key: String): Double? = prim(key)?.content?.toDoubleOrNull()

internal fun JsonObject?.bool(key: String): Boolean {
    val p = prim(key) ?: return false
    return p.booleanOrNull ?: (p.content.isNotEmpty() && p.content != "0")
}

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
