package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Момент як власна сутність, а не лист у розмові: `PH-MOM:` + JSON.
 * Байти медіа їдуть звичайними шматками [PeerFile]; кадр несе лише опис.
 * Автор — завжди відправник листа, полю на дроті не віримо.
 */
object MomentWire {

    const val TAG = "PH-MOM:"
    const val TEXT_MAX = 280
    const val MEDIA_MAX = 4
    const val DAY_MS = 24 * 60 * 60 * 1000L
    const val VIDEO_MAX_BYTES = 6 * 1024 * 1024
    const val VIDEO_MAX_MS = 15_500L
    const val CIRCLE_MAX = 32
    val REACTIONS = listOf("❤️", "😂", "😮", "😢", "👏", "🔥")

    private val KEPT = setOf(
        "name", "size", "mime", "sha256", "blob_id", "key_hex", "nonce_hex", "width", "height", "thumb", "duration_ms",
    )
    private val json = Json { ignoreUnknownKeys = true }

    enum class Reach(val wire: String) {
        ONLY_ME("me"), PEOPLE("people"), CIRCLES("circles"), CONTACTS("contacts"), EVERYONE("everyone");

        companion object {
            fun of(wire: String?): Reach? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class Audience(val reach: Reach, val circles: List<String> = emptyList()) {
        fun toJson(): JsonObject = buildJsonObject {
            put("kind", reach.wire)
            if (reach == Reach.CIRCLES) put("circles", buildJsonArray { circles.forEach { add(JsonPrimitive(it)) } })
        }

        companion object {
            fun read(o: JsonObject?): Audience? {
                val reach = Reach.of(o?.get("kind")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }) ?: return null
                if (reach != Reach.CIRCLES) return Audience(reach)
                val names = (o?.get("circles") as? JsonArray).orEmpty()
                    .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()?.trim() }
                    .filter { it.isNotEmpty() && it.length <= CIRCLE_MAX && it.none(Char::isISOControl) }
                    .distinct()
                return if (names.isEmpty()) null else Audience(reach, names)
            }
        }
    }

    data class Moment(
        val id: String,
        val author: String,
        val media: List<JsonObject>,
        val text: String,
        val audience: Audience,
        /** 0 — «залишити» (Highlights). */
        val untilMs: Long,
        val createdAtMs: Long,
        val editedAtMs: Long,
        val rev: Int,
        val deleted: Boolean = false,
    ) {
        val kept: Boolean get() = untilMs == 0L
    }

    sealed interface Op {
        data class Put(val moment: Moment) : Op
        data class Del(val id: String, val rev: Int, val atMs: Long) : Op
        data class Seen(val id: String, val atMs: Long) : Op
        data class React(val id: String, val emoji: String) : Op
    }

    fun isMoment(body: String): Boolean = body.startsWith(TAG)

    fun isId(id: String): Boolean = id.length in 8..40 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    /** Опис одного медіа, як його кладе веб, або null — це не фото й не коротке відео. */
    fun mediaOf(o: JsonObject): JsonObject? {
        val att = PeerFile.describe(o.toString()) ?: return null
        val video = att.mime.startsWith("video/")
        if (!att.mime.startsWith("image/") && !video) return null
        if (video) {
            if (att.size > VIDEO_MAX_BYTES) return null
            val ms = runCatching { o["duration_ms"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: return null
            if (ms <= 0 || ms > VIDEO_MAX_MS) return null
        }
        return JsonObject(o.filterKeys { it in KEPT })
    }

    fun encode(op: Op): String? {
        val o = when (op) {
            is Op.Put -> {
                val m = op.moment
                if (!isId(m.id) || m.media.size > MEDIA_MAX || m.text.length > TEXT_MAX) return null
                if (m.media.isEmpty() && m.text.isBlank()) return null
                buildJsonObject {
                    put("op", "put")
                    put("id", m.id)
                    put("rev", m.rev)
                    put("at", m.createdAtMs)
                    put("ed", m.editedAtMs)
                    put("until", m.untilMs)
                    put("aud", m.audience.toJson())
                    if (m.text.isNotEmpty()) put("text", m.text)
                    put("media", JsonArray(m.media))
                }
            }
            is Op.Del -> buildJsonObject {
                put("op", "del")
                put("id", op.id)
                put("rev", op.rev)
                put("at", op.atMs)
            }
            is Op.Seen -> buildJsonObject {
                put("op", "seen")
                put("id", op.id)
                put("at", op.atMs)
            }
            is Op.React -> {
                if (op.emoji.isNotEmpty() && op.emoji !in REACTIONS) return null
                buildJsonObject {
                    put("op", "react")
                    put("id", op.id)
                    put("e", op.emoji)
                }
            }
        }
        return TAG + o.toString()
    }

    /** Кадр від [fromId]; автор моменту — він і ніхто інший. */
    fun decode(body: String, fromId: String): Op? {
        if (!isMoment(body)) return null
        val o = runCatching { json.parseToJsonElement(body.substring(TAG.length)) as? JsonObject }.getOrNull() ?: return null
        fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
        fun long(k: String) = runCatching { o[k]?.jsonPrimitive?.longOrNull }.getOrNull()
        val id = str("id")?.takeIf(::isId) ?: return null
        return when (str("op")) {
            "put" -> {
                val rev = long("rev")?.toInt()?.takeIf { it >= 1 } ?: return null
                val at = long("at")?.takeIf { it > 0 } ?: return null
                val until = long("until")?.takeIf { it == 0L || it > at } ?: return null
                val aud = Audience.read(o["aud"] as? JsonObject) ?: return null
                val raw = (o["media"] as? JsonArray).orEmpty()
                if (raw.size > MEDIA_MAX) return null
                val media = raw.map { (it as? JsonObject)?.let(::mediaOf) ?: return null }
                val text = str("text").orEmpty().trim().take(TEXT_MAX)
                if (media.isEmpty() && text.isEmpty()) return null
                Op.Put(
                    Moment(
                        id = id,
                        author = fromId,
                        media = media,
                        text = text,
                        audience = aud,
                        // Чужа «доба» не довша за добу від створення.
                        untilMs = if (until == 0L) 0L else minOf(until, at + DAY_MS),
                        createdAtMs = at,
                        editedAtMs = long("ed")?.coerceAtLeast(at) ?: at,
                        rev = rev,
                    ),
                )
            }
            "del" -> Op.Del(id, long("rev")?.toInt()?.coerceAtLeast(1) ?: return null, long("at") ?: 0L)
            "seen" -> Op.Seen(id, long("at") ?: 0L)
            "react" -> str("e")?.takeIf { it.isEmpty() || it in REACTIONS }?.let { Op.React(id, it) }
            else -> null
        }
    }

    fun toJson(m: Moment): JsonObject = buildJsonObject {
        put("id", m.id)
        put("author", m.author)
        put("rev", m.rev)
        put("at", m.createdAtMs)
        put("ed", m.editedAtMs)
        put("until", m.untilMs)
        put("aud", m.audience.toJson())
        put("text", m.text)
        put("media", JsonArray(m.media))
        if (m.deleted) put("deleted", true)
    }

    fun fromJson(o: JsonObject): Moment? {
        fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
        fun long(k: String) = runCatching { o[k]?.jsonPrimitive?.longOrNull }.getOrNull()
        return Moment(
            id = str("id")?.takeIf(::isId) ?: return null,
            author = str("author") ?: return null,
            media = (o["media"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject },
            text = str("text").orEmpty(),
            audience = Audience.read(o["aud"] as? JsonObject) ?: Audience(Reach.ONLY_ME),
            untilMs = long("until") ?: return null,
            createdAtMs = long("at") ?: return null,
            editedAtMs = long("ed") ?: 0L,
            rev = long("rev")?.toInt() ?: 1,
            deleted = runCatching { (o["deleted"] as? JsonPrimitive)?.content == "true" }.getOrDefault(false),
        )
    }
}
