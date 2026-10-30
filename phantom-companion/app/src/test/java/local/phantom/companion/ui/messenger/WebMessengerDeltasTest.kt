package local.phantom.companion.ui.messenger

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebMessengerDeltasTest {

    private val me = "me"
    private fun line(id: String, from: String, to: String, body: String = "hi") =
        PeerMessage(id = id, fromId = from, toId = to, body = body, sentAtMs = 1L)

    @Test
    fun `незмінені рядки не зачіпаються, нові й змінені — зі своєю розмовою`() {
        val a1 = line("a1", me, "ann")
        val b1 = line("b1", "bob", me)
        val prev = List(2000) { line("old$it", me, "ann") } + a1 + b1
        val next = prev.dropLast(1) + b1.copy(state = PeerMessage.State.DELIVERED) + line("c1", "cat", me)
        val t = WebMessengerDeltas.touched(prev, next, me)
        assertEquals(setOf("bob", "cat"), t.convs)
        assertEquals(setOf("b1"), t.lines["bob"])
        assertNull(t.lines["ann"])
    }

    @Test
    fun `стертий рядок — теж зміна його розмови`() {
        val a = line("a", me, "ann")
        val t = WebMessengerDeltas.touched(listOf(a), emptyList(), me)
        assertEquals(setOf("ann"), t.convs)
    }

    @Test
    fun `рядок групи належить групі, а не людині`() {
        val g = PeerGroup.Group("grp1", "Сімʼя", listOf(me, "ann", "bob"))
        val m = line("g1", "ann", me, PeerGroup.encode(g, "привіт"))
        assertEquals("grp1", WebMessengerDeltas.convOf(m, me))
    }

    @Test
    fun `пачка 50 листів за вікно — один лист у веб`() = runTest {
        val out = mutableListOf<WebMessengerDeltas.Touch>()
        val s = WebMessengerDeltas.Smoother(this) { out += it }
        var log = emptyList<PeerMessage>()
        repeat(50) { i ->
            val next = log + line("m$i", "bob", me)
            s.offer(WebMessengerDeltas.touched(log, next, me))
            log = next
            advanceTimeBy(0)
        }
        runCurrent()
        assertTrue(out.isEmpty())
        advanceTimeBy(WebMessengerDeltas.WINDOW_MS + 1)
        assertEquals(1, out.size)
        assertEquals(50, out.single().lines["bob"]!!.size)
        assertTrue(out.single().json().startsWith("""{"convs":["bob"],"lines":{"bob":["m0","m1","""))
    }

    @Test
    fun `кеш рядків віддає лише різницю, без опори — null`() {
        val c = WebMessengerDeltas.RowCache()
        assertNull(c.diff("x", listOf("1" to "{a}")))
        c.baseline("x", listOf("1" to "{a}", "2" to "{b}"))
        val d = c.diff("x", listOf("1" to "{a}", "2" to "{b2}", "3" to "{c}"))!!
        assertEquals(listOf("{b2}", "{c}"), d.upsert)
        assertEquals(emptyList<String>(), d.remove)
        val gone = c.diff("x", listOf("1" to "{a}"))!!
        assertEquals(listOf("2", "3"), gone.remove)
        assertEquals(emptyList<String>(), gone.upsert)
    }

    @Test
    fun `позначка стану картки групи оновлює відкриту групу, а не лише чат автора`() {
        val org = "a1a1a1a1a1a1a1a1"
        val olena = "b2b2b2b2b2b2b2b2"
        val g = PeerGroup.Group("0f0f0f0f0f0f0f0f", "Cafe X · 19:00", listOf(org, olena, me))
        val card = local.phantom.companion.core.net.peer.PeerCard.encode(
            local.phantom.companion.core.net.peer.PeerCard.Body(
                local.phantom.companion.core.net.peer.PeerCard.Card.MEETUP, """{"v":1,"id":"m1"}""",
            ),
        )!!
        val copy = PeerMessage("cardcopy", org, me, PeerGroup.encode(g, card), sentAtMs = 100L)
        val target = WebMessengerCardMarks.targetOf(copy)
        val tap = PeerMessage(
            "tap1", olena, me,
            local.phantom.companion.core.net.peer.PeerMark.encode(
                local.phantom.companion.core.net.peer.PeerMark.Mark(
                    local.phantom.companion.core.net.peer.PeerMark.What.CARD_STATE, target, true, "rsvp:yes",
                ),
            )!!,
            sentAtMs = 200L,
        )
        val t = WebMessengerDeltas.touched(listOf(copy), listOf(copy, tap), me)
        assertTrue("група не названа: ${t.convs}", g.id in t.convs)
        assertEquals(setOf("cardcopy"), t.lines[g.id])
    }

    @Test
    fun `позначка стану картки вдвох оновлює розмову з автором і саму картку`() {
        val card = local.phantom.companion.core.net.peer.PeerCard.encode(
            local.phantom.companion.core.net.peer.PeerCard.Body(
                local.phantom.companion.core.net.peer.PeerCard.Card.MEETUP, """{"v":1,"id":"m1"}""",
            ),
        )!!
        val dm = PeerMessage("dmcard", me, "ann", card, sentAtMs = 100L)
        val tap = PeerMessage(
            "tap2", "ann", me,
            local.phantom.companion.core.net.peer.PeerMark.encode(
                local.phantom.companion.core.net.peer.PeerMark.Mark(
                    local.phantom.companion.core.net.peer.PeerMark.What.CARD_STATE, "dmcard", true, "rsvp:yes",
                ),
            )!!,
            sentAtMs = 200L,
        )
        val t = WebMessengerDeltas.touched(listOf(dm), listOf(dm, tap), me)
        assertEquals(setOf("ann"), t.convs)
        assertEquals(setOf("tap2", "dmcard"), t.lines["ann"])
    }
}
