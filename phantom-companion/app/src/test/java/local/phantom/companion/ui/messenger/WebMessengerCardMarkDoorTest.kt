package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMark
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дорога дотику по картці від тіла запиту веба до JSON відповіді й до рядка стрічки.
 *
 * Це не пошук рядків у вихідному коді: двері викликаються справжні, журнал —
 * фікстура, відправка — підміна, що кладе кадр у той самий журнал (як робить
 * `PeerMesh.send`). Помилка у формі тіла, у значенні `card_marks`, у списку
 * `missed` чи в адресатах не пройде.
 *
 * Тіло запиту — `{"value": "...", "on": true|false}` (договір, §2).
 */
class WebMessengerCardMarkDoorTest {

    private val org = "a1a1a1a1a1a1a1a1"
    private val olena = "b2b2b2b2b2b2b2b2"
    private val petro = "c3c3c3c3c3c3c3c3"
    private val group = PeerGroup.Group("0f0f0f0f0f0f0f0f", "Cafe X · 19:00", listOf(org, olena, petro))
    private val card = PeerCard.encode(PeerCard.Body(PeerCard.Card.MEETUP, """{"v":1,"id":"m1"}"""))!!
    private val sentAt = 1_760_000_000_000L

    private val copyToOlena = PeerMessage("11111111-1111-1111-1111-111111111111", org, olena, PeerGroup.encode(group, card), sentAt)
    private val copyToPetro = PeerMessage("22222222-2222-2222-2222-222222222222", org, petro, PeerGroup.encode(group, card), sentAt)
    private val copyToOrg = PeerMessage("33333333-3333-3333-3333-333333333333", org, org, PeerGroup.encode(group, card), sentAt)

    /** Усе, що телефон Олени знає й може: журнал, ключі, оголошення сцени, відправка. */
    private class Phone(
        val me: String,
        start: List<PeerMessage>,
        var keyed: Set<String>,
        var scene: Set<String>,
        /** Кому відправка не вдається (повертає null). */
        var failing: Set<String> = emptySet(),
    ) {
        val log = start.toMutableList()
        val sent = mutableListOf<Pair<String, String>>()
        private var clock = 1_760_000_100_000L

        fun door() = WebMessengerCardMarkDoor(
            log = { log.toList() },
            me = { me },
            keyed = { keyed },
            scene = { scene },
            send = { to, frame ->
                if (to in failing) {
                    null
                } else {
                    sent += to to frame
                    PeerMessage("sent-${sent.size}", me, to, frame, clock++).also { log += it }
                }
            },
            words = { "рядок#$it" },
        )
    }

    private fun phone(
        keyed: Set<String> = setOf(org, petro),
        scene: Set<String> = setOf(org, petro),
        failing: Set<String> = emptySet(),
    ) = Phone(olena, listOf(copyToOlena), keyed, scene, failing)

    private fun reply(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun JsonObject.status() = this["status"]!!.jsonPrimitive.int
    private fun JsonObject.code() = this["code"]?.jsonPrimitive?.content
    private fun JsonObject.data() = this["data"]!!.jsonObject
    private fun JsonArray.strings() = map { it.jsonPrimitive.content }

    private fun mark(value: String, on: Boolean = true) = """{"value":"$value","on":$on}"""

    @Test
    fun `дотик у групі — кадр кожному, хто вміє, а відповідь несе мою позначку й порожній missed`() {
        val p = phone()
        val r = reply(p.door().handle(copyToOlena.id, mark("rsvp:yes")))
        assertTrue(r["ok"]!!.jsonPrimitive.boolean)
        assertEquals(200, r.status())
        val data = r.data()
        assertEquals(copyToOlena.id, data["id"]!!.jsonPrimitive.content)
        assertEquals(
            """{"rsvp:yes":["$olena"]}""",
            data["card_marks"]!!.toString(),
        )
        assertEquals(emptyList<String>(), data["missed"]!!.jsonArray.strings())
        // Кадр поїхав обом, і це той самий кадр: ціль — ключ віяра (автор_мить), значення — зі словника.
        assertEquals(listOf(org, petro), p.sent.map { it.first })
        val target = "${org}_$sentAt"
        for ((_, frame) in p.sent) {
            assertEquals(PeerMark.Mark(PeerMark.What.CARD_STATE, target, true, "rsvp:yes"), PeerMark.decode(frame))
        }
        assertEquals("PH-CST:$target;+;cnN2cDp5ZXM", p.sent.first().second)
    }

    @Test
    fun `рядок стрічки, прочитаний із журналу після дотику, показує те саме, що відповідь`() {
        val p = phone()
        val data = reply(p.door().handle(copyToOlena.id, mark("dish:soup"))).data()
        // Журнал фікстури тепер несе мої кадри — так само, як `mesh.outbox` на телефоні.
        val folded = WebMessengerCardMarks.fold(p.log)
        val onMyLine = WebMessengerCardMarks.onLine(folded, copyToOlena, group.memberIds)
        assertEquals(data["card_marks"], onMyLine)
        // І копія тієї самої картки, що лежить в Петра (інший id), знаходить ту саму позначку.
        val onPetrosCopy = WebMessengerCardMarks.onLine(folded, copyToPetro, group.memberIds)
        assertEquals(onMyLine, onPetrosCopy)
        assertEquals("""{"dish:soup":["$olena"]}""", onMyLine.toString())
    }

    @Test
    fun `зняття — on=false знімає свою позначку, чужі лишаються`() {
        val p = phone()
        val door = p.door()
        door.handle(copyToOlena.id, mark("rsvp:yes"))
        // Петро свою вже поставив: його кадр лежить у журналі (дійшов раніше).
        p.log += PeerMessage(
            "from-petro", petro, olena,
            PeerMark.encode(PeerMark.Mark(PeerMark.What.CARD_STATE, "${org}_$sentAt", true, "rsvp:yes"))!!,
            1_760_000_050_000L,
        )
        val after = reply(door.handle(copyToOlena.id, mark("rsvp:yes", on = false))).data()
        assertEquals("""{"rsvp:yes":["$petro"]}""", after["card_marks"]!!.toString())
        assertEquals(PeerMark.decode(p.sent.last().second)!!.on, false)
    }

    @Test
    fun `адресат на старій збірці не дістає кадр і названий у missed`() {
        val p = phone(scene = setOf(org))
        val data = reply(p.door().handle(copyToOlena.id, mark("rsvp:yes"))).data()
        assertEquals("кадр поїхав і старій збірці", listOf(org), p.sent.map { it.first })
        assertEquals(listOf(petro), data["missed"]!!.jsonArray.strings())
        // Моя позначка при цьому є — лежить у моєму журналі.
        assertEquals("""{"rsvp:yes":["$olena"]}""", data["card_marks"]!!.toString())
    }

    @Test
    fun `адресат без ключа і той, кому відправка не вдалась, — теж у missed`() {
        val keyless = phone(keyed = setOf(org))
        assertEquals(
            listOf(petro),
            reply(keyless.door().handle(copyToOlena.id, mark("here:1"))).data()["missed"]!!.jsonArray.strings(),
        )
        val broken = phone(failing = setOf(petro))
        val data = reply(broken.door().handle(copyToOlena.id, mark("here:1"))).data()
        assertEquals(listOf(org), broken.sent.map { it.first })
        assertEquals(listOf(petro), data["missed"]!!.jsonArray.strings())
    }

    @Test
    fun `усі на старій збірці — 409 peer_old, жодного кадру, моя позначка не вдає, що поїхала`() {
        val p = phone(scene = emptySet())
        val r = reply(p.door().handle(copyToOlena.id, mark("rsvp:yes")))
        assertFalse(r["ok"]!!.jsonPrimitive.boolean)
        assertEquals(409, r.status())
        assertEquals("peer_old", r.code())
        assertEquals("рядок#${WebMessengerCardMarks.Why.PEER_OLD.res}", r["detail"]!!.jsonPrimitive.content)
        assertTrue(p.sent.isEmpty())
        assertEquals("в журнал лягло щось, чого не відправляли", 1, p.log.size)
    }

    @Test
    fun `нікому без ключа — 409 no_key`() {
        val p = phone(keyed = emptySet())
        val r = reply(p.door().handle(copyToOlena.id, mark("rsvp:yes")))
        assertEquals(409, r.status())
        assertEquals("no_key", r.code())
        assertTrue(p.sent.isEmpty())
    }

    @Test
    fun `відправка, що не вдалась нікому, — send_failed зі своїм словом, а не no_identity`() {
        val p = phone(failing = setOf(org, petro))
        val r = reply(p.door().handle(copyToOlena.id, mark("rsvp:yes")))
        assertEquals(503, r.status())
        assertEquals("send_failed", r.code())
        assertEquals("рядок#${WebMessengerCardMarks.Why.SEND_FAILED.res}", r["detail"]!!.jsonPrimitive.content)
        assertTrue(r.code() != "no_identity")
    }

    @Test
    fun `криве тіло — 422 і жодного кадру`() {
        val p = phone()
        for ((body, code) in listOf(
            null to "bad_body",
            "{}" to "bad_body",
            """{"value":"rsvp:yes"}""" to "bad_body",
            """{"value":"rsvp:perhaps","on":true}""" to "bad_value",
            """{"value":"PH-CST:x;+;y","on":true}""" to "bad_value",
        )) {
            val r = reply(p.door().handle(copyToOlena.id, body))
            assertEquals("тіло $body", 422, r.status())
            assertEquals("тіло $body", code, r.code())
        }
        assertTrue(p.sent.isEmpty())
    }

    @Test
    fun `не картка, чужий рядок і вийшов із групи — відмови зі своїм кодом`() {
        val text = PeerMessage("t1", org, olena, PeerGroup.encode(group, "привіт"), 1L)
        val foreign = PeerMessage("f1", org, petro, PeerGroup.encode(group, card), sentAt)
        val p = Phone(olena, listOf(copyToOlena, text, foreign), setOf(org, petro), setOf(org, petro))
        assertEquals("not_card", reply(p.door().handle("t1", mark("rsvp:yes"))).code())
        assertEquals("no_message", reply(p.door().handle("f1", mark("rsvp:yes"))).code())
        assertEquals("no_message", reply(p.door().handle("nope", mark("rsvp:yes"))).code())
        assertTrue(p.sent.isEmpty())
    }

    @Test
    fun `розмова вдвох — ціль id рядка, кадр співрозмовнику`() {
        val dm = PeerMessage("dm1", org, olena, card, sentAt)
        val p = Phone(olena, listOf(dm), setOf(org), setOf(org))
        val data = reply(p.door().handle("dm1", mark("eta:29333333"))).data()
        assertEquals(listOf(org), p.sent.map { it.first })
        assertEquals("dm1", PeerMark.decode(p.sent.single().second)!!.targetId)
        assertEquals("""{"eta:29333333":["$olena"]}""", data["card_marks"]!!.toString())
    }

    @Test
    fun `організатор бачить мій дотик на своїй копії тієї ж картки`() {
        // Телефон організатора: його копія картки (адресована йому самому) і мій кадр, що доїхав.
        val target = "${org}_$sentAt"
        val mine = PeerMessage(
            "from-olena", olena, org,
            PeerMark.encode(PeerMark.Mark(PeerMark.What.CARD_STATE, target, true, "rsvp:yes"))!!,
            1_760_000_100_000L,
        )
        val folded = WebMessengerCardMarks.fold(listOf(copyToOrg, mine))
        assertEquals(
            """{"rsvp:yes":["$olena"]}""",
            WebMessengerCardMarks.onLine(folded, copyToOrg, group.memberIds).toString(),
        )
    }
}
