package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerReviseTest {

    private val t0 = 1_788_517_702_898L

    private fun line(id: String, from: String, body: String, at: Long = t0) =
        PeerMessage(id = id, fromId = from, toId = if (from == "me") "them" else "me", body = body, sentAtMs = at)

    private fun edit(id: String, from: String, target: String, text: String, at: Long) =
        line(id, from, PeerRevise.encode(PeerRevise.Edit(target, text))!!, at)

    private fun unsend(id: String, from: String, target: String, at: Long) =
        line(id, from, PeerRevise.encode(PeerRevise.Unsend(target))!!, at)

    @Test
    fun `кадри читаються тим, чим складені`() {
        val text = "о сьомій; не о шостій\nі з 🇺🇦"
        assertEquals(PeerRevise.Edit("m-1", text), PeerRevise.decode(PeerRevise.encode(PeerRevise.Edit("m-1", text))!!))
        assertEquals(PeerRevise.Unsend("m-1"), PeerRevise.decode(PeerRevise.encode(PeerRevise.Unsend("m-1"))!!))
        assertNull(PeerRevise.encode(PeerRevise.Edit("m-1", "PH-LOC:50.1,30.5")))
        assertNull(PeerRevise.encode(PeerRevise.Edit("m-1", "  ")))
        assertNull(PeerRevise.encode(PeerRevise.Edit("m-1", "а".repeat(PeerRevise.MAX_TEXT_BYTES))))
        assertNull(PeerRevise.encode(PeerRevise.Edit("m;1", "так")))
    }

    @Test
    fun `правка автора переписує рядок на місці`() {
        val log = listOf(line("m1", "them", "о шостій"), edit("e1", "them", "m1", "о сьомій", t0 + 5_000))
        val m1 = PeerRevise.apply(log).log.first { it.id == "m1" }
        assertEquals("о сьомій", m1.body)
        assertEquals(t0 + 5_000, m1.editedAtMs)
        assertEquals(t0, m1.sentAtMs)
    }

    @Test
    fun `правка, складена понад 48 год після листа, отримувачем не приймається`() {
        val late = t0 + PeerRevise.EDIT_WINDOW_MS + 1
        val log = listOf(line("m1", "them", "о шостій"), edit("e1", "them", "m1", "переписав історію", late))
        assertEquals(log, PeerRevise.apply(log).log)
        val onTime = listOf(line("m1", "them", "о шостій"), edit("e1", "them", "m1", "о сьомій", t0 + PeerRevise.EDIT_WINDOW_MS))
        assertEquals("о сьомій", PeerRevise.apply(onTime).log.first().body)
        // Пізня правка не скасовує вчасної.
        val both = onTime + edit("e2", "them", "m1", "пізно", late)
        assertEquals("о сьомій", PeerRevise.apply(both).log.first { it.id == "m1" }.body)
    }

    @Test
    fun `чужа правка не чіпає рядка`() {
        val log = listOf(line("m1", "them", "о шостій"), edit("e1", "me", "m1", "я так не казав", t0 + 5_000))
        assertEquals(log, PeerRevise.apply(log).log)
        val gone = listOf(line("m1", "them", "о шостій"), unsend("u1", "me", "m1", t0 + 5_000))
        assertEquals(gone, PeerRevise.apply(gone).log)
    }

    @Test
    fun `пізніша правка автора виграє, хоч би в якому порядку доїхали`() {
        val base = line("m1", "them", "раз")
        val a = edit("e1", "them", "m1", "два", t0 + 1_000)
        val b = edit("e2", "them", "m1", "три", t0 + 2_000)
        assertEquals("три", PeerRevise.apply(listOf(base, a, b)).log.first().body)
        assertEquals("три", PeerRevise.apply(listOf(base, b, a)).log.first().body)
        val once = PeerRevise.apply(listOf(base, b)).log
        assertEquals("три", PeerRevise.apply(once + a).log.first().body)
    }

    @Test
    fun `правка, що обігнала рядок, не створює його й чекає`() {
        val early = edit("e1", "them", "m1", "о сьомій", t0 + 5_000)
        val alone = PeerRevise.apply(listOf(early)).log
        assertEquals(listOf(early), alone)
        assertTrue(alone.none { PeerOutbox.isSpoken(it) })
        val arrived = PeerRevise.apply(alone + line("m1", "them", "о шостій")).log
        assertEquals("о сьомій", arrived.first { it.id == "m1" }.body)
    }

    @Test
    fun `стирання робить надгробок, забирає шматки й перемагає правку`() {
        val blob = "a".repeat(PeerFile.BLOB_ID_CHARS)
        val file = line("m1", "them", """{"blob_id":"$blob","name":"фото.jpg","mime":"image/jpeg","size":2,"sha256":"${"0".repeat(64)}","key_hex":"00"}""")
        val chunk = line("c1", "them", PeerFile.encodeChunk(blob, 0, 1, byteArrayOf(1, 2)))
        val log = listOf(
            file, chunk,
            unsend("u1", "them", "m1", t0 + 1_000),
            edit("e1", "them", "m1", "інше", t0 + 9_000),
        )
        val applied = PeerRevise.apply(log)
        val m1 = applied.log.first { it.id == "m1" }
        assertEquals("", m1.body)
        assertEquals(t0 + 1_000, m1.deletedAtMs)
        assertTrue(applied.log.none { it.id == "c1" })
        assertEquals(listOf(file), applied.unsent)
        assertEquals(applied.log, PeerRevise.apply(applied.log).log)
        assertTrue(PeerRevise.apply(applied.log).unsent.isEmpty())
    }

    @Test
    fun `у групі конверт лишається, міняються лише слова`() {
        val group = PeerGroup.Group("a1b2c3d4e5f6", "ланка", listOf("me", "them"))
        val log = listOf(
            line("g1", "them", PeerGroup.encode(group, "о шостій")),
            edit("e1", "them", "g1", "о сьомій", t0 + 1_000),
        )
        val g1 = PeerRevise.apply(log).log.first { it.id == "g1" }
        assertEquals("о сьомій", PeerGroup.decode(g1.body)?.text)
        assertEquals(group.id, PeerGroup.decode(g1.body)?.group?.id)
        val gone = PeerRevise.apply(log + unsend("u1", "them", "g1", t0 + 2_000)).log.first { it.id == "g1" }
        assertEquals("", PeerGroup.decode(gone.body)?.text)
    }

    @Test
    fun `тіло, складене пристроєм, правкою не стає словом`() {
        val place = line("m1", "them", PeerPayload.encodePlace(50.45, 30.52, 25))
        val log = listOf(place, edit("e1", "them", "m1", "я тут", t0 + 1_000))
        assertEquals(place, PeerRevise.apply(log).log.first { it.id == "m1" })
    }

    private fun photo(caption: String, kind: String = "image") =
        """{"blob_id":"${"a".repeat(32)}","key_hex":"00ff","sha256":"ab","size":10,"name":"p.jpg","mime":"image/jpeg","kind":"$kind","caption":"$caption"}"""

    @Test
    fun `підпис фото правиться на місці, решта опису вкладення та сама`() {
        val log = listOf(line("p1", "them", photo("море")), edit("e1", "them", "p1", "**море** вранці", t0 + 1_000))
        val p1 = PeerRevise.apply(log).log.first { it.id == "p1" }
        val a = PeerFile.describe(p1.body)!!
        assertEquals("**море** вранці", a.caption)
        assertEquals(PeerFile.describe(photo("море"))!!.copy(caption = "**море** вранці"), a)
        assertEquals(t0 + 1_000, p1.editedAtMs)

        val group = PeerGroup.Group("a1b2c3d4e5f6", "ланка", listOf("me", "them"))
        val inGroup = listOf(line("g1", "them", PeerGroup.encode(group, photo(""))), edit("e1", "them", "g1", "нове", t0 + 1_000))
        val g1 = PeerRevise.apply(inGroup).log.first { it.id == "g1" }
        assertEquals(group.id, PeerGroup.decode(g1.body)?.group?.id)
        assertEquals("нове", PeerFile.describe(PeerGroup.decode(g1.body)!!.text)!!.caption)

        val voice = line("v1", "them", photo("", kind = "voice"))
        assertEquals(voice, PeerRevise.apply(listOf(voice, edit("e2", "them", "v1", "слова", t0 + 1_000))).log.first { it.id == "v1" })
    }

    @Test
    fun `кадр правки не рахується листом`() {
        val frame = edit("e1", "them", "m1", "о сьомій", t0)
        assertEquals(0, PeerOutbox.unread(listOf(frame), "them", 0L))
        assertEquals(0, PeerOutbox.waiting(listOf(frame.copy(fromId = "me", state = PeerMessage.State.QUEUED))))
        val tomb = line("m1", "me", "", t0).copy(deletedAtMs = t0 + 1, state = PeerMessage.State.QUEUED)
        assertTrue(PeerOutbox.due(listOf(tomb), t0 + 10).isEmpty())
    }
    @Test
    fun `історія змін — перша версія лишається тут, новіші вище`() {
        val sent = listOf(line("m1", "them", "о шостій"), edit("e1", "them", "m1", "о сьомій", t0 + 1_000))
        val once = PeerRevise.apply(sent).log
        val origin = once.single { it.id == PeerRevise.ORIGIN + "m1" }
        assertEquals(PeerMessage.State.DELIVERED, origin.state)
        assertEquals(PeerOutbox.unread(sent.take(1), "them", 0L), PeerOutbox.unread(once, "them", 0L))
        assertEquals(once, PeerRevise.apply(once).log)

        val twice = PeerRevise.apply(once + edit("e2", "them", "m1", "о восьмій", t0 + 2_000)).log
        assertEquals(1, twice.count { it.id == PeerRevise.ORIGIN + "m1" })
        val m1 = twice.first { it.id == "m1" }
        assertEquals(
            listOf("о восьмій" to t0 + 2_000, "о сьомій" to t0 + 1_000, "о шостій" to t0),
            PeerRevise.history(twice, m1).map { it.text to it.atMs },
        )
        assertTrue(PeerRevise.history(twice, line("m2", "them", "інше")).isEmpty())
    }

    @Test
    fun `історія підпису фото — підписи, а не опис вкладення`() {
        val log = PeerRevise.apply(listOf(line("p1", "me", photo("море")), edit("e1", "me", "p1", "море вранці", t0 + 1_000))).log
        assertEquals(listOf("море вранці", "море"), PeerRevise.history(log, log.first { it.id == "p1" }).map { it.text })
    }

    @Test
    fun `стертий рядок не лишає слів у кадрах правки`() {
        val edited = PeerRevise.apply(listOf(line("m1", "me", "о шостій"), edit("e1", "me", "m1", "о сьомій", t0 + 1_000))).log
        val gone = PeerRevise.apply(edited + unsend("u1", "me", "m1", t0 + 2_000)).log
        assertTrue(gone.none { PeerRevise.isEdit(it.body) })
        assertTrue(PeerPurge.line(edited, edited.first { it.id == "m1" }).ids.containsAll(listOf("m1", "e1", PeerRevise.ORIGIN + "m1")))
    }
}
