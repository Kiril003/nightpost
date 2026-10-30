package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * «Мій день»: фото на добу. Дріт — звичайний опис вкладення ([PeerFile]) з
 * полем `moment`, тож байти, підпис, черга й складання ті самі, що в фото
 * розмови, а стара збірка покаже його просто фотографією.
 */
object PeerMoment {

    const val FIELD = "moment"
    const val TTL_MS = 24 * 60 * 60 * 1000L
    const val CAPTION_MAX = 200
    const val EVERYONE = "everyone"
    const val PEOPLE = "people"
    const val CIRCLE = "circle:"
    private const val CIRCLE_NAME_MAX = 32
    private const val SKEW_MS = 60 * 60 * 1000L

    private val KEPT = setOf("name", "size", "mime", "sha256", "blob_id", "key_hex", "nonce_hex", "width", "height", "thumb")
    private val json = Json { ignoreUnknownKeys = true }

    data class Moment(
        val id: String,
        val fromId: String,
        val sentAtMs: Long,
        val untilMs: Long,
        val audience: String,
        val caption: String,
        val media: JsonObject,
        val lines: List<PeerMessage>,
    )

    fun audienceOf(raw: String?): String {
        val a = raw?.trim().orEmpty()
        if (a == EVERYONE) return EVERYONE
        if (a.startsWith(CIRCLE)) {
            val name = a.removePrefix(CIRCLE).trim()
            if (name.isNotEmpty() && name.length <= CIRCLE_NAME_MAX && name.none { it.isISOControl() }) return CIRCLE + name
        }
        return PEOPLE
    }

    /** Тіло листа-моменту з опису фото від веба, або null — це не фото. */
    fun compose(photo: JsonObject, audience: String, nowMs: Long): String? {
        val caption = (photo["caption"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(CAPTION_MAX).orEmpty()
        val body = buildJsonObject {
            photo.filterKeys { it in KEPT }.forEach { (k, v) -> put(k, v) }
            if (caption.isNotEmpty()) put("caption", JsonPrimitive(caption))
            put(FIELD, buildJsonObject {
                put("until", JsonPrimitive(nowMs + TTL_MS))
                put("aud", JsonPrimitive(audienceOf(audience)))
            })
        }.toString()
        val att = PeerFile.describe(body) ?: return null
        return body.takeIf { att.mime.startsWith("image/") }
    }

    private fun fields(body: String): JsonObject? {
        if (!body.startsWith("{") || !body.contains("\"$FIELD\"")) return null
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return o.takeIf { it[FIELD] is JsonObject }
    }

    fun isMoment(body: String): Boolean = read(body, 0L) != null

    fun read(line: PeerMessage): Moment? = read(line.body, line.sentAtMs)?.copy(id = idOf(line.body), fromId = line.fromId, lines = listOf(line))

    private fun idOf(body: String): String = PeerFile.describe(body)?.blobId.orEmpty()

    private fun read(body: String, sentAtMs: Long): Moment? {
        val o = fields(body) ?: return null
        val att = PeerFile.describe(body) ?: return null
        if (!att.mime.startsWith("image/")) return null
        val m = o[FIELD] as JsonObject
        val declared = runCatching { m["until"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: return null
        val aud = runCatching { m["aud"]?.jsonPrimitive?.content }.getOrNull()
        return Moment(
            id = att.blobId,
            fromId = "",
            sentAtMs = sentAtMs,
            untilMs = minOf(declared, sentAtMs + TTL_MS),
            audience = audienceOf(aud),
            caption = att.caption.take(CAPTION_MAX),
            media = JsonObject(o.filterKeys { it in KEPT }),
            lines = emptyList(),
        )
    }

    private fun alive(m: Moment, nowMs: Long): Boolean = m.untilMs > nowMs && m.sentAtMs <= nowMs + SKEW_MS

    /** Живі моменти, свіжі першими; копії одного розсилання — один момент. */
    fun live(log: List<PeerMessage>, nowMs: Long): List<Moment> =
        log.asSequence()
            .filter { it.deletedAtMs == 0L }
            .mapNotNull { read(it) }
            .filter { alive(it, nowMs) }
            .groupBy { it.fromId to it.id }
            .values
            .map { copies -> copies.first().copy(lines = copies.flatMap { it.lines }) }
            .sortedByDescending { it.sentAtMs }

    /** Рядки, чий день минув, — разом із тими, що вдають час із майбутнього. */
    fun expired(log: List<PeerMessage>, nowMs: Long): List<PeerMessage> =
        log.filter { line -> line.deletedAtMs == 0L && read(line)?.let { !alive(it, nowMs) } == true }
}
