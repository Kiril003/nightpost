package local.phantom.companion.ui.messenger

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.peer.ContactRequestBook
import local.phantom.companion.core.net.peer.PeerContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMessengerRequestsTest {

    private class Mem : ContactRequestBook.Store {
        var raw: String? = null
        override fun read() = raw
        override fun write(raw: String) { this.raw = raw }
    }

    private class Fake : WebMessengerRequests.Host {
        val sent = mutableListOf<Pair<String, String>>()
        var ids = 0
        var online = true
        val people = mutableMapOf(
            "p1" to WebMessengerRequests.Person("Олена", own = false),
            "pc" to WebMessengerRequests.Person("Мій ПК", own = true),
        )
        override fun owner() = "me"
        override fun person(id: String) = people[id]
        override suspend fun myName() = " Кирило "
        override fun newId() = "id-${++ids}"
        override fun send(toId: String, body: String): Boolean {
            if (online) sent += toId to body
            return online
        }
    }

    private val host = Fake()
    private val book = ContactRequestBook(Mem())
    private val route = WebMessengerRequests(book, host)

    private fun call(method: String, path: String, body: String? = null): JsonObject =
        Json.parseToJsonElement(runBlocking { route.handle(method, path.trim('/').split('/'), body) }).jsonObject

    private fun JsonObject.data() = getValue("data")
    private fun JsonObject.status() = getValue("status").jsonPrimitive.content.toInt()

    @Test
    fun `маршрути належать запитам і лише їм`() {
        assertTrue(route.claims(listOf("messenger", "requests")))
        assertTrue(route.claims(listOf("messenger", "requests", "r", "accept")))
        assertFalse(route.claims(listOf("messenger", "reminders")))
    }

    @Test
    fun `запит іде кадром ПК і лягає у вихідні тієї ж форми`() {
        val r = call("POST", "/messenger/requests", """{"contact_id":"p1","text":"  привіт "}""")
        assertEquals(200, r.status())
        val row = r.data().jsonObject
        assertEquals(
            setOf("id", "direction", "peer_node_id", "name", "text", "created_at", "delivery"),
            row.keys,
        )
        assertEquals("out", row.getValue("direction").jsonPrimitive.content)
        assertEquals("p1", row.getValue("peer_node_id").jsonPrimitive.content)
        assertEquals("привіт", row.getValue("text").jsonPrimitive.content)
        assertEquals("sent", row.getValue("delivery").jsonPrimitive.content)
        val frame = host.sent.single()
        assertEquals("p1", frame.first)
        assertEquals(PeerContact.Op.Request("id-1", "Кирило", "привіт"), PeerContact.decode(frame.second))
        val list = call("GET", "/messenger/requests").data().jsonObject
        assertEquals(1, list.getValue("outgoing").jsonArray.size)
        assertEquals(0, list.getValue("incoming").jsonArray.size)
    }

    @Test
    fun `відмови мають ті самі статуси, що на вузлі`() {
        assertEquals(404, call("POST", "/messenger/requests", """{"contact_id":"nobody"}""").status())
        assertEquals(400, call("POST", "/messenger/requests", """{"contact_id":"pc"}""").status())
        assertEquals(422, call("POST", "/messenger/requests", """{"contact_id":"p1","text":"https://x.io"}""").status())
        assertEquals(422, call("POST", "/messenger/requests", """{"contact_id":"p1","text":"${"я".repeat(501)}"}""").status())
        assertEquals(422, call("POST", "/messenger/requests", """{"contact_id":""}""").status())
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun `без зв'язку запит у черзі, повтор не множить рядок`() {
        host.online = false
        val first = call("POST", "/messenger/requests", """{"contact_id":"p1"}""").data().jsonObject
        assertEquals("queued", first.getValue("delivery").jsonPrimitive.content)
        host.online = true
        val second = call("POST", "/messenger/requests", """{"contact_id":"p1"}""").data().jsonObject
        assertEquals(first.getValue("id"), second.getValue("id"))
        assertEquals("sent", second.getValue("delivery").jsonPrimitive.content)
        assertEquals(1, host.sent.size)
    }

    @Test
    fun `31-й запит за добу - 429`() {
        host.people.clear()
        repeat(30) { host.people["p$it"] = WebMessengerRequests.Person("N", false) }
        host.people["extra"] = WebMessengerRequests.Person("N", false)
        repeat(30) { assertEquals(200, call("POST", "/messenger/requests", """{"contact_id":"p$it"}""").status()) }
        assertEquals(429, call("POST", "/messenger/requests", """{"contact_id":"extra"}""").status())
    }

    @Test
    fun `вхідний запит приймається - кадр згоди йде і рядок зникає`() {
        book.absorb("me", "p1", "Олена", PeerContact.Op.Request("r9", "Олена", "привіт"))
        val inc = call("GET", "/messenger/requests").data().jsonObject.getValue("incoming").jsonArray
        assertEquals("r9", inc.single().jsonObject.getValue("id").jsonPrimitive.content)
        val r = call("POST", "/messenger/requests/r9/accept")
        assertEquals("p1", r.data().jsonObject.getValue("conversation_id").jsonPrimitive.content)
        assertEquals("p1", r.data().jsonObject.getValue("contact_id").jsonPrimitive.content)
        assertEquals(PeerContact.Op.Accept("r9"), PeerContact.decode(host.sent.single().second))
        assertTrue(book.listOpen("me").isEmpty())
        assertEquals(404, call("POST", "/messenger/requests/r9/accept").status())
    }

    @Test
    fun `відхилення тихе`() {
        book.absorb("me", "p1", "Олена", PeerContact.Op.Request("r9", "Олена", "x"))
        assertEquals(200, call("POST", "/messenger/requests/r9/decline").status())
        assertTrue(host.sent.isEmpty())
        assertEquals("declined", book.find("me", "r9")?.state)
    }

    @Test
    fun `відкликання шле кадр і чужий напрям не чіпає`() {
        val id = call("POST", "/messenger/requests", """{"contact_id":"p1"}""").data().jsonObject
            .getValue("id").jsonPrimitive.content
        host.sent.clear()
        assertEquals(404, call("POST", "/messenger/requests/$id/accept").status())
        assertEquals(200, call("POST", "/messenger/requests/$id/withdraw").status())
        assertEquals(PeerContact.Op.Withdraw(id), PeerContact.decode(host.sent.single().second))
        assertEquals(404, call("POST", "/messenger/requests/$id/withdraw").status())
    }
}
