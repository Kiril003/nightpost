package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Запит у контакти — дріт ПК (`messenger/blobs.py`, `contact:*`), слово в слово.
 *
 * Телефон і вузол ПК шлють один одному ТОЙ САМИЙ кадр, тож окремого перекладу
 * між ними немає: `\u0001phantom-kind:<рід>\n<тіло>`. Тіло запиту — JSON
 * `{id,name,text}`, відкликання й згода несуть лише id. Збіг з ПК тримає вектор
 * `contact_requests_vector.json`, який читають обидва боки.
 */
object PeerContact {

    const val TAG = "\u0001phantom-kind:contact:"

    const val ID_LIMIT = 64

    sealed interface Op {
        val id: String

        data class Request(override val id: String, val name: String, val text: String) : Op
        data class Withdraw(override val id: String) : Op
        data class Accept(override val id: String) : Op
    }

    fun isContact(body: String): Boolean = body.startsWith(TAG)

    fun encode(op: Op): String = when (op) {
        is Op.Request -> TAG + "request\n" + buildJsonObject {
            put("id", clip(op.id, ID_LIMIT))
            put("name", op.name)
            put("text", op.text)
        }
        is Op.Withdraw -> TAG + "withdraw\n" + clip(op.id, ID_LIMIT)
        is Op.Accept -> TAG + "accept\n" + clip(op.id, ID_LIMIT)
    }.toString()

    fun decode(body: String): Op? {
        if (!isContact(body)) return null
        val head = body.substringBefore('\n')
        val rest = body.substringAfter('\n', "")
        val kind = head.removePrefix(TAG).trim().split(' ', '\t').firstOrNull().orEmpty()
        return when (kind) {
            "request" -> {
                val data = runCatching { Json.parseToJsonElement(rest).jsonObject }.getOrNull() ?: return null
                val id = clip(text(data["id"]), ID_LIMIT).ifEmpty { return null }
                Op.Request(id, text(data["name"]), text(data["text"]))
            }
            "withdraw" -> clip(rest.trim(), ID_LIMIT).takeIf { it.isNotEmpty() }?.let(Op::Withdraw)
            "accept" -> clip(rest.trim(), ID_LIMIT).takeIf { it.isNotEmpty() }?.let(Op::Accept)
            else -> null
        }
    }

    /** Python `str(x or "")`: порожнє й нуль — це «нічого». */
    private fun text(e: kotlinx.serialization.json.JsonElement?): String {
        val p = e as? JsonPrimitive ?: return ""
        val c = p.content
        return if (c.isEmpty() || c == "0" || c == "false" || c == "null") "" else c
    }

    /** Python ріже по кодових точках, не по половинках сурогатної пари. */
    fun clip(s: String, n: Int): String =
        if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))
}
