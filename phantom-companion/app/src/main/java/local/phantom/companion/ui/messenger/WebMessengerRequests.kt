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
import local.phantom.companion.core.net.peer.ContactRequestBook
import local.phantom.companion.core.net.peer.PeerContact

/**
 * `/messenger/requests` — ті самі пʼять маршрутів і ті самі форми, що на вузлі
 * ПК (`routes_messenger_requests.py`). Кадр їде як `contact:*` через пошту
 * телефона; відхилення відправнику нічого не шле.
 */
internal class WebMessengerRequests(private val book: ContactRequestBook, private val host: Host) {

    class Person(val name: String, val own: Boolean)

    interface Host {
        fun owner(): String
        fun person(id: String): Person?
        suspend fun myName(): String
        fun newId(): String
        fun send(toId: String, body: String): Boolean
    }

    fun claims(seg: List<String>): Boolean = seg.size >= 2 && seg[1] == "requests"

    suspend fun handle(method: String, seg: List<String>, body: String?): String = runCatching {
        when {
            seg.size == 2 && method == "GET" -> list()
            seg.size == 2 && method == "POST" -> send(body)
            seg.size == 4 && method == "POST" -> when (seg[3]) {
                "accept" -> accept(seg[2])
                "decline" -> decline(seg[2])
                "withdraw" -> withdraw(seg[2])
                else -> fail(404, "not_found", "")
            }
            else -> fail(404, "not_found", "")
        }
    }.getOrElse { fail(500, "requests_failed", "") }

    private fun out(r: ContactRequestBook.Row): JsonObject = buildJsonObject {
        put("id", r.id)
        put("direction", r.dir)
        put("peer_node_id", r.peer)
        put("name", r.name)
        put("text", r.text)
        put("created_at", Instant.ofEpochMilli(r.atMs).toString())
        put("delivery", if (r.sent) "sent" else "queued")
    }

    private fun list(): String {
        val rows = book.listOpen(host.owner())
        return ok(
            buildJsonObject {
                put("incoming", buildJsonArray { rows.filter { it.dir == ContactRequestBook.IN }.forEach { add(out(it)) } })
                put("outgoing", buildJsonArray { rows.filter { it.dir == ContactRequestBook.OUT }.forEach { add(out(it)) } })
            },
        )
    }

    private suspend fun send(raw: String?): String {
        val o = runCatching { Json.parseToJsonElement(raw ?: "").jsonObject }.getOrNull() ?: return fail(422, "bad_body", "")
        val contactId = (o["contact_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (contactId.isEmpty() || contactId.length > PeerContact.ID_LIMIT) return fail(422, "bad_body", "contact_id")
        val rawText = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (rawText != null && rawText.codePointCount(0, rawText.length) > ContactRequestBook.TEXT_LIMIT) {
            return fail(422, "bad_body", "text")
        }
        val owner = host.owner()
        val who = host.person(contactId) ?: return fail(404, "contact_not_found", "contact not found")
        if (who.own) return fail(400, "own_device", "own device")
        val text = ContactRequestBook.cleanText(rawText)
        if (ContactRequestBook.hasLink(text)) return fail(422, "links_not_allowed", "links are not allowed")
        val (row0, fresh) = try {
            book.createOutgoing(owner, host.newId(), contactId, who.name, text)
        } catch (_: ContactRequestBook.QuotaExceeded) {
            return fail(429, "daily_limit", "daily request limit")
        }
        var row = row0
        if (fresh || !row.sent) {
            val frame = PeerContact.encode(PeerContact.Op.Request(row.id, host.myName().trim(), row.text))
            if (host.send(contactId, frame)) {
                book.mark(owner, row.id, ContactRequestBook.OUT, ContactRequestBook.PENDING, sent = true)
                row = row.copy(sent = true)
            }
        }
        return ok(out(row))
    }

    private fun own(id: String, dir: String): ContactRequestBook.Row? =
        book.find(host.owner(), id)?.takeIf { it.dir == dir && it.state == ContactRequestBook.PENDING }

    private fun accept(id: String): String {
        val owner = host.owner()
        val row = own(id, ContactRequestBook.IN) ?: return fail(404, "request_not_found", "request not found")
        host.person(row.peer) ?: return fail(404, "contact_not_found", "contact not found")
        book.mark(owner, id, ContactRequestBook.IN, "accepted")
        host.send(row.peer, PeerContact.encode(PeerContact.Op.Accept(id)))
        return ok(buildJsonObject { put("conversation_id", row.peer); put("contact_id", row.peer) })
    }

    private fun decline(id: String): String {
        own(id, ContactRequestBook.IN) ?: return fail(404, "request_not_found", "request not found")
        book.mark(host.owner(), id, ContactRequestBook.IN, "declined")
        return ok(buildJsonObject { })
    }

    private fun withdraw(id: String): String {
        val row = own(id, ContactRequestBook.OUT) ?: return fail(404, "request_not_found", "request not found")
        book.mark(host.owner(), id, ContactRequestBook.OUT, "withdrawn")
        if (host.person(row.peer) != null) host.send(row.peer, PeerContact.encode(PeerContact.Op.Withdraw(id)))
        return ok(buildJsonObject { })
    }

    private fun ok(data: JsonElement) = WebMessengerReply.ok(data)

    private fun fail(status: Int, code: String, detail: String) = WebMessengerReply.fail(status, code, detail)
}
