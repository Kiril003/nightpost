package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.peer.MomentBook
import local.phantom.companion.core.net.peer.MomentWire
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerFrames
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMessengerMomentsTest {

    private val t0 = 1_790_000_000_000L
    private val blob = "0123456789abcdef0123456789abcdef"
    private val photo = """{"name":"p.jpg","size":1200,"mime":"image/jpeg","sha256":"${"ab".repeat(32)}","blob_id":"$blob","key_hex":"${"cd".repeat(32)}","nonce_hex":"${"ef".repeat(12)}"}"""

    /** Телефон без мережі: те, що «пішло», лежить у [sent], і його можна покласти в інший телефон. */
    private class Phone(val me: String, var clock: Long) : WebMessengerMoments.Host {
        val sent = mutableListOf<PeerMessage>()
        val bytesTo = mutableListOf<String>()
        val assembled = mutableListOf<String>()
        val dropped = mutableListOf<Pair<String, String>>()
        val forgotten = mutableSetOf<String>()
        val traces = mutableListOf<String>()
        val seenIds = mutableSetOf<String>()
        var stored = MomentBook()
        var prefsRaw: String? = null
        var mutedIds = setOf<String>()
        var crowd = listOf(WebMessengerMoments.Person("olena", "Olena"), WebMessengerMoments.Person("petro", "Petro"))
        var inbox = listOf<PeerMessage>()
        private var n = 0
        override fun me() = me
        override fun people() = crowd
        override fun book() = stored
        override fun save(book: MomentBook) { stored = book }
        override fun send(toId: String, body: String): PeerMessage? =
            PeerMessage(id = "$me-${sent.size}", fromId = me, toId = toId, body = body, sentAtMs = clock).also { sent += it }
        override fun sendBytes(toId: String, attachment: PeerFile.Attachment) { bytesTo += toId }
        override fun assemble(fromId: String, media: JsonObject) { assembled += fromId }
        override fun state(conversationId: String, attachment: PeerFile.Attachment) =
            if (conversationId == WebMessengerMoments.OWN_DIR) "ready" else "receiving"
        override fun dropBytes(conversationId: String, blobId: String) { dropped += conversationId to blobId }
        override fun log() = inbox + sent
        override fun forget(ids: Set<String>) { forgotten += ids }
        override fun seen() = seenIds
        override fun markSeen(id: String) { seenIds += id }
        override fun prefs() = prefsRaw
        override fun savePrefs(raw: String) { prefsRaw = raw }
        override fun muted() = mutedIds
        override fun setMuted(ids: Set<String>) { mutedIds = ids }
        override fun trace(line: String) { traces += line }
        var foreign = setOf<String>()
        override fun reaches(peerId: String) = peerId !in foreign
        override fun newId() = "mom_${me}_${n++}"
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun h(r: WebMessengerMoments, m: String, seg: List<String>, body: String? = null) =
        json(kotlinx.coroutines.runBlocking { r.handle(m, seg, body) })
    private val seg = listOf("messenger", "moments")
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun list(r: WebMessengerMoments) = h(r, "GET", seg)["data"]!!.jsonArray.map { it.jsonObject }

    /** Усе, що [from] надіслав [to], приїжджає до нього. */
    private fun deliver(from: Phone, to: Phone, r: WebMessengerMoments) {
        from.sent.filter { it.toId == to.me }.forEach { r.absorb(it) }
        from.sent.removeAll { it.toId == to.me }
    }

    @Test fun `пост — власний кадр, не лист розмови, кожному з чатів кадр і байти`() {
        val me = Phone("me000000", t0)
        val r = WebMessengerMoments(me) { me.clock }
        val res = h(r, "POST", seg, """{"media":[$photo],"text":"хліб","audience":{"kind":"contacts"}}""")
        assertEquals("true", res.s("ok"))
        assertEquals(listOf("olena", "petro"), me.sent.map { it.toId })
        assertEquals(listOf("olena", "petro"), me.bytesTo)
        assertTrue(me.sent.all { PeerFrames.kindOf(it.body) == PeerFrames.Kind.MOMENT })
        assertFalse(WebMessengerKinds.isVisible(me.sent[0].body))

        val own = list(r).single()
        assertEquals("true", own.s("mine"))
        assertEquals("2", own.s("to_count"))
        assertEquals("contacts", (own["audience"] as JsonObject).s("kind"))
        assertEquals("хліб", own.s("text"))
        assertEquals("ready", (own["media"] as JsonArray)[0].jsonObject.s("state"))
    }

    @Test fun `людина іншого профілю не отримує момент ні як «контакти», ні вибрана поіменно`() {
        val me = Phone("me000000", t0).apply { foreign = setOf("petro") }
        val r = WebMessengerMoments(me) { me.clock }
        h(r, "POST", seg, """{"media":[$photo],"text":"робота","audience":{"kind":"contacts"}}""")
        h(r, "POST", seg, """{"text":"т","audience":{"kind":"people"},"to":["olena","petro"]}""")
        assertEquals(listOf("olena", "olena"), me.sent.map { it.toId })
        assertEquals(listOf("olena"), me.bytesTo)
        val only = h(r, "POST", seg, """{"text":"т","audience":{"kind":"people"},"to":["petro"]}""")
        assertEquals("false", only.s("ok"))
        assertEquals(2, me.sent.size)
    }

    @Test fun `коло — лише ті з кола, з ким є чат, аудиторія несе назви кіл`() {
        val me = Phone("me000000", t0)
        val r = WebMessengerMoments(me) { me.clock }
        h(r, "POST", seg, """{"text":"тиша","audience":{"kind":"circles","circles":["close","work"]},"to":["petro","stranger"]}""")
        assertEquals(listOf("petro"), me.sent.map { it.toId })
        val aud = list(r).single()["audience"] as JsonObject
        assertEquals(listOf("close", "work"), aud["circles"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `«Лише я» нікуди не їде, але стоїть у списку, «Усі» без публічного профілю — відмова`() {
        val me = Phone("me000000", t0)
        val r = WebMessengerMoments(me) { me.clock }
        h(r, "POST", seg, """{"text":"для себе","audience":{"kind":"me"}}""")
        assertTrue(me.sent.isEmpty())
        assertEquals("0", list(r).single().s("to_count"))
        val every = h(r, "POST", seg, """{"text":"всім","audience":{"kind":"everyone"}}""")
        assertEquals("everyone_unavailable", every.s("code"))
        assertEquals("false", h(r, "GET", seg + "prefs")["data"]!!.jsonObject.s("everyone"))
    }

    @Test fun `останню аудиторію памʼятає`() {
        val me = Phone("me000000", t0)
        val r = WebMessengerMoments(me) { me.clock }
        h(r, "POST", seg, """{"text":"т","audience":{"kind":"people"},"to":["olena"],"keep":true}""")
        val p = h(r, "GET", seg + "prefs")["data"]!!.jsonObject
        assertEquals("people", (p["audience"] as JsonObject).s("kind"))
        assertEquals(listOf("olena"), p["to"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", p.s("keep"))
    }

    @Test fun `правка й видалення доходять тим самим людям, у них момент зникає разом з байтами`() {
        val me = Phone("me000000", t0)
        val olena = Phone("olena", t0)
        val mine = WebMessengerMoments(me) { me.clock }
        val hers = WebMessengerMoments(olena) { olena.clock }
        val id = h(mine, "POST", seg, """{"media":[$photo],"text":"кава","audience":{"kind":"people"},"to":["olena"]}""")["data"]!!.jsonObject.s("id")
        deliver(me, olena, hers)
        assertEquals(listOf("me000000"), olena.assembled)
        assertEquals("кава", list(hers).single().s("text"))

        me.clock += 1000
        h(mine, "PATCH", seg + id, """{"text":"чай"}""")
        assertEquals(listOf("olena"), me.sent.map { it.toId })
        deliver(me, olena, hers)
        val edited = list(hers).single()
        assertEquals("чай", edited.s("text"))
        assertTrue(edited.s("edited_ms").toLong() > 0)

        h(mine, "DELETE", seg + id)
        assertTrue(list(mine).isEmpty())
        assertTrue(me.dropped.contains(WebMessengerMoments.OWN_DIR to blob))
        deliver(me, olena, hers)
        assertTrue(list(hers).isEmpty())
        assertTrue(olena.dropped.contains("me000000" to blob))
    }

    @Test fun `PATCH аудиторії звужує коло — хто випав — при наступному обміні втрачає момент і байти`() {
        val me = Phone("me000000", t0)
        val olena = Phone("olena", t0)
        val petro = Phone("petro", t0)
        val mine = WebMessengerMoments(me) { me.clock }
        val hers = WebMessengerMoments(olena) { olena.clock }
        val his = WebMessengerMoments(petro) { petro.clock }
        val id = h(mine, "POST", seg, """{"media":[$photo],"text":"кава","audience":{"kind":"contacts"}}""")["data"]!!.jsonObject.s("id")
        deliver(me, olena, hers)
        deliver(me, petro, his)
        assertEquals(1, list(hers).size)
        assertEquals(1, list(his).size)

        me.clock += 1000
        val res = h(mine, "PATCH", seg + id, """{"audience":{"kind":"circles","circles":["close"]},"to":["olena"]}""")
        assertEquals("true", res.s("ok"))
        val own = res["data"]!!.jsonObject
        assertEquals("circles", (own["audience"] as JsonObject).s("kind"))
        assertEquals("1", own.s("to_count"))
        assertEquals(listOf("olena"), own["to"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("кава", own.s("text"))
        assertEquals("аудиторія — не правка підпису", "0", own.s("edited_ms"))
        assertTrue(me.dropped.contains("petro" to blob))
        assertFalse(me.dropped.contains(WebMessengerMoments.OWN_DIR to blob))

        deliver(me, olena, hers)
        deliver(me, petro, his)
        assertEquals("circles", (list(hers).single()["audience"] as JsonObject).s("kind"))
        assertTrue(list(his).isEmpty())
        assertTrue(petro.dropped.contains("me000000" to blob))
    }

    @Test fun `PATCH аудиторії розширює коло — новому — кадр і байти, порожня аудиторія — відмова`() {
        val me = Phone("me000000", t0)
        val petro = Phone("petro", t0)
        val mine = WebMessengerMoments(me) { me.clock }
        val his = WebMessengerMoments(petro) { petro.clock }
        val id = h(mine, "POST", seg, """{"media":[$photo],"text":"т","audience":{"kind":"people"},"to":["olena"]}""")["data"]!!.jsonObject.s("id")
        me.sent.clear(); me.bytesTo.clear()
        h(mine, "PATCH", seg + id, """{"audience":{"kind":"contacts"}}""")
        assertEquals(setOf("olena", "petro"), me.sent.map { it.toId }.toSet())
        assertEquals(listOf("petro"), me.bytesTo)
        deliver(me, petro, his)
        assertEquals("т", list(his).single().s("text"))
        assertEquals(listOf("me000000"), petro.assembled)

        assertEquals("no_one", h(mine, "PATCH", seg + id, """{"audience":{"kind":"people"},"to":["stranger"]}""").s("code"))
        assertEquals("bad_audience", h(mine, "PATCH", seg + id, """{"audience":{"kind":"bogus"}}""").s("code"))
        assertEquals("bad_body", h(mine, "PATCH", seg + id, """{}""").s("code"))
        assertEquals("2", list(mine).single().s("to_count"))
    }

    @Test fun `чужий момент не правиться й не видаляється`() {
        val me = Phone("me000000", t0)
        val olena = Phone("olena", t0)
        val mine = WebMessengerMoments(me) { me.clock }
        val hers = WebMessengerMoments(olena) { olena.clock }
        val id = h(mine, "POST", seg, """{"text":"моє","audience":{"kind":"contacts"}}""")["data"]!!.jsonObject.s("id")
        deliver(me, olena, hers)
        assertEquals("gone", h(hers, "PATCH", seg + id, """{"text":"її"}""").s("code"))
        assertEquals("gone", h(hers, "DELETE", seg + id).s("code"))
    }

    @Test fun `автор бачить, хто бачив і який смайл, відповідь смайлом іде лише автору`() {
        val me = Phone("me000000", t0)
        val olena = Phone("olena", t0)
        val mine = WebMessengerMoments(me) { me.clock }
        val hers = WebMessengerMoments(olena) { olena.clock }
        val id = h(mine, "POST", seg, """{"text":"т","audience":{"kind":"contacts"}}""")["data"]!!.jsonObject.s("id")
        deliver(me, olena, hers)
        h(hers, "POST", seg + id + "seen")
        h(hers, "POST", seg + id + "seen")
        assertEquals("💩 не з набору", "bad_emoji", h(hers, "POST", seg + id + "react", """{"emoji":"💩"}""").s("code"))
        h(hers, "POST", seg + id + "react", """{"emoji":"🔥"}""")
        assertEquals("одна позначка «бачила», хоч дивилась двічі", 2, olena.sent.size)
        assertTrue(olena.sent.all { it.toId == "me000000" })
        assertEquals("🔥", list(hers).single().s("my_reaction"))

        olena.sent.forEach { mine.absorb(it) }
        val own = list(mine).single()
        val viewer = own["viewers"]!!.jsonArray.single().jsonObject
        assertEquals("olena", viewer.s("id"))
        assertEquals("Olena", viewer.s("name"))
        assertEquals("🔥", viewer.s("emoji"))
        assertEquals("1", (own["reactions"] as JsonObject).s("🔥"))
    }

    @Test fun `доба минає — момент і байти геть, keep — у Highlights профілю`() {
        val me = Phone("me000000", t0)
        val r = WebMessengerMoments(me) { me.clock }
        h(r, "POST", seg, """{"media":[$photo],"audience":{"kind":"contacts"}}""")
        h(r, "POST", seg, """{"text":"назавжди","audience":{"kind":"contacts"},"keep":true}""")
        me.clock = t0 + MomentWire.DAY_MS + 1
        assertEquals(1, r.sweep())
        assertTrue(me.dropped.contains(WebMessengerMoments.OWN_DIR to blob))
        assertEquals(listOf("назавжди"), list(r).map { it.s("text") })
        assertEquals(listOf("назавжди"), r.highlights("me000000").map { it.text })
        assertEquals("назавжди", h(r, "GET", seg + "highlights" + "me")["data"]!!.jsonArray.single().jsonObject.s("text"))
    }

    @Test fun `відпрацьовані кадри йдуть із журналу — приїжджі одразу, свої — після доставки`() {
        val me = Phone("me000000", t0)
        val r = WebMessengerMoments(me) { me.clock }
        h(r, "POST", seg, """{"text":"т","audience":{"kind":"people"},"to":["olena"]}""")
        val outgoing = me.sent.single()
        val arrived = PeerMessage(id = "in1", fromId = "olena", toId = "me000000", body = MomentWire.encode(MomentWire.Op.Seen("mom_x_000001", t0))!!, sentAtMs = t0)
        me.inbox = listOf(arrived)
        r.sweep()
        assertEquals(setOf("in1"), me.forgotten)
        me.sent[0] = outgoing.copy(state = PeerMessage.State.DELIVERED)
        r.sweep()
        assertTrue(outgoing.id in me.forgotten)
    }

    @Test fun `приглушена людина зникає зі списку, але не з книги`() {
        val me = Phone("me000000", t0)
        val olena = Phone("olena", t0)
        val mine = WebMessengerMoments(me) { me.clock }
        val hers = WebMessengerMoments(olena) { olena.clock }
        h(mine, "POST", seg, """{"text":"т","audience":{"kind":"contacts"}}""")
        deliver(me, olena, hers)
        h(hers, "POST", seg + "mute" + "me000000", """{"on":true}""")
        assertTrue(list(hers).isEmpty())
        h(hers, "POST", seg + "mute" + "me000000", """{"on":false}""")
        assertEquals(1, list(hers).size)
    }

    @Test fun `слід у logcat — лише лічильники й id, без тексту`() {
        val me = Phone("me000000", t0)
        val olena = Phone("olena", t0)
        val mine = WebMessengerMoments(me) { me.clock }
        val hers = WebMessengerMoments(olena) { olena.clock }
        h(mine, "POST", seg, """{"text":"таємний підпис","audience":{"kind":"contacts"}}""")
        deliver(me, olena, hers)
        list(hers)
        assertTrue(me.traces.any { it.startsWith("post ") && "to=2" in it })
        assertTrue(olena.traces.any { it.startsWith("arrive op=put") })
        assertTrue(olena.traces.any { it == "list live=1 mine=0" })
        assertTrue((me.traces + olena.traces).none { "таємний" in it })
    }

    @Test fun `порожній момент і повтор кадру нічого не ламають`() {
        val me = Phone("me000000", t0)
        val r = WebMessengerMoments(me) { me.clock }
        assertEquals("empty", h(r, "POST", seg, """{"text":"  ","audience":{"kind":"contacts"}}""").s("code"))
        val frame = PeerMessage(id = "x", fromId = "olena", toId = "me000000", body = MomentWire.encode(MomentWire.Op.Put(
            MomentWire.Moment("mom_olena01", "olena", emptyList(), "привіт", MomentWire.Audience(MomentWire.Reach.CONTACTS), t0 + 1000, t0, t0, 1),
        ))!!, sentAtMs = t0)
        r.absorb(frame)
        r.absorb(frame)
        assertEquals(1, list(r).size)
        assertEquals(1, me.traces.count { it.startsWith("arrive") })
        assertNull(list(r).single()["viewers"])
    }

    @Test fun `revoke прибирає людину зі збереженої аудиторії та відкликає живі моменти`() {
        val me = Phone("me000000", t0)
        val olena = Phone("olena", t0)
        val mine = WebMessengerMoments(me) { me.clock }
        val hers = WebMessengerMoments(olena) { olena.clock }
        h(mine, "POST", seg, """{"media":[$photo],"text":"таємниця","audience":{"kind":"people"},"to":["olena","petro"]}""")
        deliver(me, olena, hers)
        assertEquals(1, list(hers).size)

        val rev = h(mine, "POST", seg + "revoke" + "olena", "{}")
        assertEquals("true", rev.s("ok"))
        val p = h(mine, "GET", seg + "prefs")["data"]!!.jsonObject
        assertEquals(listOf("petro"), p["to"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("1", list(mine).single().s("to_count"))
        assertTrue(me.dropped.contains("olena" to blob))

        deliver(me, olena, hers)
        assertTrue(list(hers).isEmpty())
    }

    @Test fun `невдалий POST моменту з медіа прибирає завантажений блоб із OWN_DIR`() {
        val me = Phone("me000000", t0)
        val r = WebMessengerMoments(me) { me.clock }
        val res = h(r, "POST", seg, """{"media":[$photo],"text":"нікому","audience":{"kind":"people"},"to":[]}""")
        assertEquals("no_one", res.s("code"))
        assertTrue(me.dropped.contains(WebMessengerMoments.OWN_DIR to blob))
    }
}
