package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMark
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Позначки стану на картці зустрічі й рахунку (`PH-CST:`,
 * `docs/scene/MEETUP_CONTRACT.md` §2).
 *
 * Найтонше місце — ГРУПА. Лист у групі — це N окремих копій, кожна зі своїм
 * id ([PeerGroup.collapse]), тож у Олени рядок картки має один id, у Петра —
 * інший, а в організатора — третій. Позначка, прибита до id копії, на
 * сусідньому апараті не знайшла б картки ніколи: «іду» Олени Петро не побачив
 * би, а рахунок не зійшовся б. Тому ціль позначки в групі — ключ віяра
 * (автор + мить), той самий, яким група закріплює рядок ([PeerGroup.Pin]).
 */
class WebMessengerCardMarksTest {

    private val org = "a1a1a1a1a1a1a1a1"
    private val olena = "b2b2b2b2b2b2b2b2"
    private val petro = "c3c3c3c3c3c3c3c3"
    private val group = PeerGroup.Group("0f0f0f0f0f0f0f0f", "Cafe X · 19:00", listOf(org, olena, petro))

    private val card = PeerCard.encode(PeerCard.Body(PeerCard.Card.MEETUP, """{"v":1,"id":"m1"}"""))!!

    private fun copy(id: String, to: String, at: Long = 1_760_000_000_000L) =
        PeerMessage(id = id, fromId = org, toId = to, body = PeerGroup.encode(group, card), sentAtMs = at)

    private fun mark(id: String, from: String, target: String, value: String, on: Boolean = true, at: Long) =
        PeerMessage(
            id = id, fromId = from, toId = org,
            body = PeerMark.encode(PeerMark.Mark(PeerMark.What.CARD_STATE, target, on, value))!!,
            sentAtMs = at,
        )

    @Test
    fun `копії однієї картки в групі мають одну ціль позначки`() {
        val toOlena = copy("11111111-1111-1111-1111-111111111111", olena)
        val toPetro = copy("22222222-2222-2222-2222-222222222222", petro)
        val target = WebMessengerCardMarks.targetOf(toOlena)
        assertEquals(target, WebMessengerCardMarks.targetOf(toPetro))
        assertNotEquals("ціль групи — id копії, а він різний на кожному апараті", toOlena.id, target)
        // І ціль мусить пролазити в кадр — інакше позначку не скласти.
        val frame = WebMessengerCardMarks.frame(target, WebMessengerCardMarks.Ask("rsvp:yes", true))!!
        assertEquals(target, PeerMark.decode(frame)!!.targetId)
        assertEquals(PeerMark.What.CARD_STATE, PeerMark.decode(frame)!!.what)
    }

    @Test
    fun `інша мить — інший рядок, навіть від того ж автора`() {
        assertNotEquals(
            WebMessengerCardMarks.targetOf(copy("x", olena, at = 1L)),
            WebMessengerCardMarks.targetOf(copy("y", olena, at = 2L)),
        )
    }

    @Test
    fun `у розмові вдвох ціль — сам id рядка`() {
        val line = PeerMessage(id = "33333333-3333-3333-3333-333333333333", fromId = org, toId = olena, body = card, sentAtMs = 5L)
        assertEquals(line.id, WebMessengerCardMarks.targetOf(line))
    }

    @Test
    fun `кому їде позначка`() {
        val toOlena = copy("11111111-1111-1111-1111-111111111111", olena)
        assertEquals(listOf(org, petro), WebMessengerCardMarks.recipients(toOlena, group, me = olena))
        val dm = PeerMessage(id = "d", fromId = org, toId = olena, body = card, sentAtMs = 5L)
        assertEquals(listOf(org), WebMessengerCardMarks.recipients(dm, null, me = olena))
        assertEquals(listOf(olena), WebMessengerCardMarks.recipients(dm, null, me = org))
    }

    @Test
    fun `згортання — лише позначки стану, лише учасники, зняте знято`() {
        val target = WebMessengerCardMarks.targetOf(copy("c1", olena))
        val stranger = "d4d4d4d4d4d4d4d4"
        val log = listOf(
            copy("c1", olena),
            mark("m1", olena, target, "rsvp:yes", at = 10),
            mark("m2", petro, target, "rsvp:no", at = 11),
            mark("m3", petro, target, "rsvp:no", on = false, at = 12),
            mark("m4", petro, target, "rsvp:yes", at = 13),
            mark("m5", olena, target, "dish:soup", at = 14),
            mark("m6", petro, target, "dish:soup", at = 15),
            mark("m7", stranger, target, "rsvp:yes", at = 16),
            // Смайл на ту саму ціль — інший рід, у позначки стану не тече.
            PeerMessage(
                "r1", petro, org,
                PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, target, true, "rsvp:yes"))!!, 17,
            ),
        )
        val marks = WebMessengerCardMarks.of(WebMessengerCardMarks.fold(log), target, allowed = group.memberIds)
        assertEquals(
            mapOf("rsvp:yes" to listOf(olena, petro), "dish:soup" to listOf(olena, petro)),
            marks,
        )
        assertEquals(emptyMap<String, List<String>>(), WebMessengerCardMarks.of(WebMessengerCardMarks.fold(log), "nope", group.memberIds))
    }

    @Test
    fun `готівка — cash і id людини у формі відбитка ключа`() {
        val ok = "cash:a1b2c3d4e5f60718"
        assertEquals(
            WebMessengerCardMarks.Parsed.Ok(WebMessengerCardMarks.Ask(ok, true)),
            WebMessengerCardMarks.parse("""{"value":"$ok","on":true}"""),
        )
        for (bad in listOf("cash:", "cash:${"a".repeat(65)}", "cash:A1B2C3D4E5F60718", "cash:a1b2c3d4e5f6071", "cash:olena-peer")) {
            assertEquals(
                bad,
                WebMessengerCardMarks.Parsed.Bad(WebMessengerCardMarks.BAD_VALUE),
                WebMessengerCardMarks.parse("""{"value":"$bad","on":true}"""),
            )
        }
    }

    @Test
    fun `тіло дотику — значення зі словника договору і явне on`() {
        for (v in listOf("rsvp:yes", "rsvp:maybe", "rsvp:no", "eta:29333333", "dish:soup_2-a", "keep:1", "here:1", "spread:1")) {
            assertEquals(
                v,
                WebMessengerCardMarks.Parsed.Ok(WebMessengerCardMarks.Ask(v, true)),
                WebMessengerCardMarks.parse("""{"value":"$v","on":true}"""),
            )
        }
        assertEquals(
            WebMessengerCardMarks.Parsed.Ok(WebMessengerCardMarks.Ask("keep:1", false)),
            WebMessengerCardMarks.parse("""{"value":"keep:1","on":false}"""),
        )
        val bad = listOf(
            null, "", "nope", "{}",
            """{"value":"rsvp:yes"}""", // без on напрям вгадувати не можна
            """{"value":"rsvp:yes","on":"true"}""",
            """{"value":"rsvp:perhaps","on":true}""",
            """{"value":"eta:soon","on":true}""",
            """{"value":"spread:2","on":true}""",
            """{"value":"dish:${"x".repeat(37)}","on":true}""",
            """{"value":"dish:борщ","on":true}""",
            """{"value":"keep:2","on":true}""",
            """{"value":"PH-CST:x;+;y","on":true}""",
            """{"value":"rsvp:yes\n","on":true}""",
        )
        for (b in bad) {
            assertTrue("пропущено криве тіло $b", WebMessengerCardMarks.parse(b) is WebMessengerCardMarks.Parsed.Bad)
        }
    }

    @Test
    fun `голос за час, вибір часу і +1 — у словнику, поза межами — ні`() {
        for (v in listOf("slot:0", "slot:2", "lock:1", "plus:1", "bring:Торт на день народження")) {
            assertEquals(
                v,
                WebMessengerCardMarks.Parsed.Ok(WebMessengerCardMarks.Ask(v, true)),
                WebMessengerCardMarks.parse("""{"value":"$v","on":true}"""),
            )
        }
        for (v in listOf("slot:3", "slot:-1", "lock:9", "plus:2", "bring:", "bring:a\nb")) {
            assertTrue(v, WebMessengerCardMarks.parse("""{"value":"$v","on":true}""") is WebMessengerCardMarks.Parsed.Bad)
        }
    }

    @Test
    fun `ціль, що не лізе в кадр, — не кадр`() {
        assertNull(WebMessengerCardMarks.frame("a;b", WebMessengerCardMarks.Ask("keep:1", true)))
    }

    // ── що міст вирішує про дотик (WebMessengerCardMarks.plan) ───────────────

    private val ask = WebMessengerCardMarks.Ask("rsvp:yes", true)
    private val keyed = setOf(org, olena, petro)

    private fun groupLog(): List<PeerMessage> = listOf(
        copy("11111111-1111-1111-1111-111111111111", olena),
        copy("22222222-2222-2222-2222-222222222222", petro),
    )

    private fun send(p: WebMessengerCardMarks.Plan) = p as WebMessengerCardMarks.Plan.Send
    private fun refuse(p: WebMessengerCardMarks.Plan) = p as WebMessengerCardMarks.Plan.Refuse

    @Test
    fun `дотик по картці групи — ціль — ключ віяра, їде всім крім мене, кадр складає міст`() {
        val log = groupLog()
        val p = send(WebMessengerCardMarks.plan(log, olena, log[0].id, ask, keyed, keyed))
        assertEquals(WebMessengerCardMarks.targetOf(log[0]), p.target)
        assertEquals(listOf(org, petro), p.to)
        assertEquals(emptyList<String>(), p.keyless)
        assertEquals(group.memberIds, p.members)
        val mark = PeerMark.decode(p.frame)!!
        assertEquals(p.target, mark.targetId)
        assertEquals("rsvp:yes", mark.value)
    }

    @Test
    fun `учасник без ключа не дістане позначку, і це названо`() {
        val log = groupLog()
        val p = send(WebMessengerCardMarks.plan(log, olena, log[0].id, ask, keyed - petro, keyed - petro))
        assertEquals(listOf(org), p.to)
        assertEquals(listOf(petro), p.keyless)
        // Поїхала лише організатору, а Петро — ні: «не всім» видно у відповіді.
        assertEquals(listOf(petro), WebMessengerCardMarks.missed(p, sentTo = listOf(org)))
    }

    @Test
    fun `відправка, що повернула нічого, теж у missed`() {
        val log = groupLog()
        val p = send(WebMessengerCardMarks.plan(log, olena, log[0].id, ask, keyed, keyed))
        assertEquals(listOf(petro), WebMessengerCardMarks.missed(p, sentTo = listOf(org)))
        assertEquals(emptyList<String>(), WebMessengerCardMarks.missed(p, sentTo = listOf(org, petro)))
    }

    @Test
    fun `нікому без ключа — 409 no_key, а не мовчазний успіх`() {
        val log = groupLog()
        val r = refuse(WebMessengerCardMarks.plan(log, olena, log[0].id, ask, emptySet(), emptySet()))
        assertEquals(409, r.status)
        assertEquals("no_key", r.code)
        assertEquals(WebMessengerCardMarks.Why.NO_KEY, r.why)
    }

    @Test
    fun `вийшов із групи — писати позначку не можна`() {
        val outsider = "d4d4d4d4d4d4d4d4"
        val log = groupLog() + PeerMessage("x9", org, outsider, PeerGroup.encode(group, card), 1_760_000_000_000L)
        val r = refuse(WebMessengerCardMarks.plan(log, outsider, "x9", ask, keyed + outsider, keyed + outsider))
        assertEquals(409, r.status)
        assertEquals("not_in_group", r.code)
    }

    @Test
    fun `не картка, чужий рядок і порожня особа — відмови зі своїм кодом`() {
        val text = PeerMessage("t1", org, olena, PeerGroup.encode(group, "привіт"), 1L)
        val log = groupLog() + text
        assertEquals("not_card", refuse(WebMessengerCardMarks.plan(log, olena, "t1", ask, keyed, keyed)).code)
        assertEquals("no_message", refuse(WebMessengerCardMarks.plan(log, olena, "nope", ask, keyed, keyed)).code)
        // Рядок, до якого я не дотична: не мій лист.
        val foreign = PeerMessage("f1", org, petro, card, 2L)
        assertEquals("no_message", refuse(WebMessengerCardMarks.plan(log + foreign, olena, "f1", ask, keyed, keyed)).code)
        assertEquals("no_identity", refuse(WebMessengerCardMarks.plan(log, "", log[0].id, ask, keyed, keyed)).code)
    }

    @Test
    fun `розмова вдвох — ціль — id рядка, їде співрозмовнику`() {
        val dm = PeerMessage("dm1", org, olena, card, 3L)
        val p = send(WebMessengerCardMarks.plan(listOf(dm), olena, "dm1", ask, keyed, keyed))
        assertEquals("dm1", p.target)
        assertEquals(listOf(org), p.to)
        assertNull(p.group)
    }

    // ── збірка 18 і старші: позначки лише тим, хто сам сказав, що вміє ────────

    @Test
    fun `адресат без оголошення сцени не дістане кадр — старій збірці він показався б сирим дротом`() {
        val log = groupLog()
        // Петро має ключ, але його профіль не казав про сцену (збірка 15 чи 17).
        val p = send(WebMessengerCardMarks.plan(log, olena, log[0].id, ask, keyed, scene = setOf(org, olena)))
        assertEquals("кадр поїхав і старій збірці", listOf(org), p.to)
        assertEquals(listOf(petro), p.older)
        assertEquals(emptyList<String>(), p.keyless)
        // «Не всім» видно у відповіді, а не лише в логах.
        assertEquals(listOf(petro), WebMessengerCardMarks.missed(p, sentTo = listOf(org)))
    }

    @Test
    fun `без ключа й зі старою збіркою — окремі списки, разом у missed`() {
        val log = groupLog()
        val p = send(WebMessengerCardMarks.plan(log, olena, log[0].id, ask, keyed - petro, scene = setOf(org)))
        assertEquals(listOf(org), p.to)
        assertEquals(listOf(petro), p.keyless)
        val three = "e5e5e5e5e5e5e5e5"
        val wide = PeerGroup.Group(group.id, group.name, group.memberIds + three)
        val line = PeerMessage("w1", org, olena, PeerGroup.encode(wide, card), 1_760_000_000_000L)
        val q = send(WebMessengerCardMarks.plan(listOf(line), olena, "w1", ask, setOf(org, petro), scene = setOf(org)))
        assertEquals(listOf(org), q.to)
        assertEquals(listOf(petro), q.older)
        assertEquals(listOf(three), q.keyless)
        assertEquals(listOf(three, petro), WebMessengerCardMarks.missed(q, sentTo = listOf(org)))
    }

    @Test
    fun `усі адресати на старій збірці — 409 peer_old, а не кадр у порожнечу`() {
        val log = groupLog()
        val r = refuse(WebMessengerCardMarks.plan(log, olena, log[0].id, ask, keyed, scene = emptySet()))
        assertEquals(409, r.status)
        assertEquals("peer_old", r.code)
        assertEquals(WebMessengerCardMarks.Why.PEER_OLD, r.why)
    }

    @Test
    fun `слова відмов — окремі рядки, а відправка, що не вдалась, не називається «немає особи»`() {
        val res = WebMessengerCardMarks.Why.entries.map { it.res }
        assertEquals("два слова ділять один рядок", res.size, res.toSet().size)
        assertTrue(WebMessengerCardMarks.Why.SEND_FAILED.res != WebMessengerCardMarks.Why.NO_IDENTITY.res)
        assertTrue(WebMessengerCardMarks.Why.PEER_OLD.res != WebMessengerCardMarks.Why.NO_KEY.res)
    }

    // ── K2: the organiser may cancel or move the plan (`cancel:1`, `at:<epochMinutes>`) ──

    @Test
    fun `K2 cancel and at are in the vocabulary, their near misses are not`() {
        for (v in listOf("cancel:1", "at:29333333")) {
            assertEquals(
                v,
                WebMessengerCardMarks.Parsed.Ok(WebMessengerCardMarks.Ask(v, true)),
                WebMessengerCardMarks.parse("""{"value":"$v","on":true}"""),
            )
        }
        for (v in listOf("cancel:0", "at:", "at:-5", "at:12345678901", "cancel:1\\n")) {
            assertTrue(v, WebMessengerCardMarks.parse("""{"value":"$v","on":true}""") is WebMessengerCardMarks.Parsed.Bad)
        }
    }

    @Test
    fun `K2 only the card author may cancel or move, and only a meetup card`() {
        val log = groupLog()
        val mine = log[0]
        val cancel = WebMessengerCardMarks.Ask("cancel:1", true)
        assertTrue(WebMessengerCardMarks.plan(log, org, mine.id, cancel, keyed, keyed) is WebMessengerCardMarks.Plan.Send)
        for (ask in listOf(cancel, WebMessengerCardMarks.Ask("at:29333333", true))) {
            val r = WebMessengerCardMarks.plan(log, olena, mine.id, ask, keyed, keyed) as? WebMessengerCardMarks.Plan.Refuse
            assertEquals("${ask.value} by a member", 403, r?.status)
            assertEquals("not_organizer", r?.code)
            assertEquals("NOT_ORGANIZER", r?.why?.name)
        }
        val bill = PeerCard.encode(PeerCard.Body(PeerCard.Card.BILL, """{"v":1,"id":"b1"}"""))!!
        val billLine = PeerMessage("bill-1", org, olena, PeerGroup.encode(group, bill), 1_760_000_000_500L)
        val r = WebMessengerCardMarks.plan(log + billLine, org, "bill-1", cancel, keyed, keyed) as? WebMessengerCardMarks.Plan.Refuse
        assertEquals(422, r?.status)
        assertEquals("bad_mark", r?.code)
        assertEquals(WebMessengerCardMarks.Why.BAD_MARK, r?.why)
    }

    @Test
    fun `K2 marksOf keeps cancel and at only from the card author, other values unchanged`() {
        val line = copy("c1", olena)
        val target = WebMessengerCardMarks.targetOf(line)
        val log = listOf(
            line,
            mark("k1", olena, target, "cancel:1", at = 10),
            mark("k2", org, target, "cancel:1", at = 11),
            mark("k3", petro, target, "at:29333333", at = 12),
            mark("k4", org, target, "at:29333400", at = 13),
            mark("k5", olena, target, "rsvp:yes", at = 14),
            mark("k6", petro, target, "keep:1", at = 15),
        )
        val folded = WebMessengerCardMarks.fold(log)
        val expected = mapOf(
            "cancel:1" to listOf(org),
            "at:29333400" to listOf(org),
            "rsvp:yes" to listOf(olena),
            "keep:1" to listOf(petro),
        )
        assertEquals(expected, WebMessengerCardMarks.marksOf(folded, line, group.memberIds))
        assertEquals(
            "the feed row must show what marksOf keeps",
            WebMessengerCardMarks.json(expected),
            WebMessengerCardMarks.onLine(folded, line, group.memberIds),
        )
    }

    @Test
    fun `K2 reach - the marks door refuses a member's cancel and carries the organiser's`() {
        fun door(me: String, log: MutableList<PeerMessage>, sent: MutableList<String>) = WebMessengerCardMarkDoor(
            log = { log.toList() },
            me = { me },
            keyed = { keyed },
            scene = { keyed },
            send = { to, frame ->
                sent += to
                PeerMessage("s-${sent.size}", me, to, frame, 1_760_000_100_000L + sent.size).also { log += it }
            },
            words = { "w#$it" },
        )
        val body = """{"value":"cancel:1","on":true}"""

        val olenaLog = mutableListOf(copy("11111111-1111-1111-1111-111111111111", olena))
        val olenaSent = mutableListOf<String>()
        val refused = door(olena, olenaLog, olenaSent).handle(olenaLog[0].id, body)
        assertTrue("a member's cancel is not refused as not_organizer: $refused", refused.contains("\"code\":\"not_organizer\""))
        assertTrue(refused.contains("\"status\":403"))
        assertEquals(emptyList<String>(), olenaSent)

        val orgLog = groupLog().toMutableList()
        val orgSent = mutableListOf<String>()
        val taken = door(org, orgLog, orgSent).handle(orgLog[0].id, body)
        assertTrue("the organiser's cancel did not go through: $taken", taken.contains("\"status\":200"))
        assertTrue(taken, taken.contains(""""card_marks":{"cancel:1":["$org"]}"""))
        assertEquals(listOf(olena, petro), orgSent)
    }
}
