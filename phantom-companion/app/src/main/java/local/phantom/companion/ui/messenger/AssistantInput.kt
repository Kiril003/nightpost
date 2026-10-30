package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerFile

/**
 * Що з листа бачить модель. Текст — як є. Опис вкладення несе ключ і сіль
 * наскрізного шифру: з нього йдуть лише розшифровка голосового й підпис,
 * а без них — нічого (null). Тіло, де трапилась назва поля ключа (навіть
 * екранована чи в підписі), але яке не розбирається як опис, теж не йде.
 */
internal object AssistantInput {

    private val MEDIA_KEYS = listOf("key_hex", "nonce_hex", "blob_id")
    private val SPACES = Regex("\\s+")

    private fun keyed(s: String) = MEDIA_KEYS.any { it in s }

    private fun parse(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body.trim()) as? JsonObject }.getOrNull()

    private fun text(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)

    fun promptOf(body: String): String? {
        if (!keyed(body)) return body
        val o = parse(body) ?: return null
        return listOf("transcript", "caption")
            .mapNotNull { text(o, it) }
            .filterNot(::keyed)
            .joinToString("\n")
            .ifEmpty { null }
    }

    /** Другий і далі аркуш пачки: підпис несе перший, відповідь — теж йому. */
    fun isBatchTail(body: String): Boolean =
        keyed(body) && ((parse(body)?.get("batch_index") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) > 0

    /** Рід і рядок для переліку розмов: опис вкладення показуємо підписом чи іменем, не дротом. */
    fun listLine(body: String): Pair<String, String?> {
        // Картка помічника («Гроші», «День») — рід; її слово складе веб, сирий `PH-CRD:` у рядок не йде.
        PeerCard.decode(body.trim())?.let { return it.what.webType to null }
        val att = PeerFile.describe(body.trim())
        if (att == null) return "text" to promptOf(body)?.trim()?.replace(SPACES, " ")?.ifEmpty { null }
        val spoken = att.kind == PeerFile.VOICE || att.kind == PeerFile.VIDEO_NOTE
        val line = att.caption.takeUnless(::keyed)?.ifBlank { null } ?: att.name.takeUnless { spoken || keyed(it) }
        return att.kind to line?.trim()?.replace(SPACES, " ")?.ifEmpty { null }
    }
}
