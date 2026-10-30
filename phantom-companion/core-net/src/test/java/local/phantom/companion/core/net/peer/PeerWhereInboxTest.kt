package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T0 = 1_790_000_000_000L

class PeerWhereInboxTest {

    private val from = "aaaa1111"
    private val cell = PeerWhereGrid.cellOf(50.45, 30.52, PeerWhereGrid.Level.DISTRICT)

    private fun area(at: Long, ttl: Long = 600_000L, c: PeerWhereGrid.Cell = cell) =
        PeerWhere.encode(PeerWhere.Area(c, at, at + ttl, 1))!!

    private fun take(inbox: PeerWhereInbox.Inbox, body: String, now: Long) =
        PeerWhereInbox.absorb(inbox, from, body, now, allowed = true)

    @Test
    fun `позиція не переживає свого строку`() {
        val inbox = take(PeerWhereInbox.Inbox(), area(T0, ttl = 600_000L), T0)
        assertNotNull(PeerWhereInbox.standingFor(inbox, from, T0 + 599_999L))
        assertNull("протухле віддалося до мітли", PeerWhereInbox.standingFor(inbox, from, T0 + 600_000L))
        assertTrue(PeerWhereInbox.live(inbox, T0 + 600_000L).isEmpty())
        assertTrue("мітла лишила протухле", PeerWhereInbox.sweep(inbox, T0 + 600_000L).standing.isEmpty())
    }

    @Test
    fun `обіцяне «назавжди» стискається до стелі`() {
        val forever = PeerWhere.encode(PeerWhere.Area(cell, T0, Long.MAX_VALUE / 2, 1))!!
        val inbox = take(PeerWhereInbox.Inbox(), forever, T0)
        assertNotNull(PeerWhereInbox.standingFor(inbox, from, T0 + PeerWhereInbox.MAX_TTL_MS - 1))
        assertNull(PeerWhereInbox.standingFor(inbox, from, T0 + PeerWhereInbox.MAX_TTL_MS))
    }

    @Test
    fun `прийняте вже протухлим або з майбутнього не лягає`() {
        assertTrue(take(PeerWhereInbox.Inbox(), area(T0 - 700_000L), T0).standing.isEmpty())
        assertTrue(take(PeerWhereInbox.Inbox(), area(T0 + 3 * 60_000L), T0).standing.isEmpty())
    }

    @Test
    fun `історії немає — лише остання точка`() {
        var inbox = PeerWhereInbox.Inbox()
        val cells = (0 until 50).map { PeerWhereGrid.Cell(cell.level, cell.row + it, 0) }
        cells.forEachIndexed { i, c -> inbox = take(inbox, area(T0 + i * 1_000L, c = c), T0 + i * 1_000L) }
        assertEquals(1, inbox.standing.size)
        assertEquals(cells.last(), (inbox.standing.getValue(from).position as PeerWhere.Area).cell)
        inbox = take(inbox, area(T0, c = cells.first()), T0 + 50_000L)
        assertEquals("старший лист відкотив людину назад", cells.last(), (inbox.standing.getValue(from).position as PeerWhere.Area).cell)
    }

    @Test
    fun `у стані ні відправника, ні приймача немає куди скласти слід`() {
        val holders = listOf(
            PeerWhereInbox.Inbox::class.java,
            PeerWhereInbox.Standing::class.java,
            PeerWhereBeacon.State::class.java,
            PeerWhereBeacon.Lane::class.java,
        )
        val lists = holders.flatMap { k ->
            k.declaredFields.filter {
                List::class.java.isAssignableFrom(it.type) || it.type.isArray ||
                    Set::class.java.isAssignableFrom(it.type)
            }.map { "${k.simpleName}.${it.name}" }
        }
        assertEquals("зʼявилось поле, куди лягає послідовність", emptyList<String>(), lists)
    }

    private fun row(id: String, fromId: String, toId: String, body: String, at: Long, state: PeerMessage.State) =
        PeerMessage(id = id, fromId = fromId, toId = toId, body = body, sentAtMs = at, state = state)

    @Test
    fun `журнал не тримає ні прийнятого, ні доставленого, ні обігнаного`() {
        val journal = (0 until 30).flatMap { i ->
            val t = T0 + i * 60_000L
            listOf(
                row("in$i", "them", "me", area(t), t, PeerMessage.State.DELIVERED),
                row("out$i", "me", "them", area(t), t, if (i % 3 == 0) PeerMessage.State.DELIVERED else PeerMessage.State.QUEUED),
            )
        } + row("text", "them", "me", "привіт", T0, PeerMessage.State.DELIVERED)
        val now = T0 + 29 * 60_000L
        val left = journal.filterNot { it.id in PeerWhereInbox.spent(journal, now) }
        assertEquals(listOf("text", "out29").sorted(), left.map { it.id }.sorted())
    }

    @Test
    fun `«зникнути» стирає в усіх і глушить маяк`() {
        var s = PeerWhereBeacon.State()
        listOf("b", "c").forEach { s = PeerWhereBeacon.grant(s, whereFriend(it), PeerWhere.Precision.EXACT, T0)!!.state }
        val before = PeerWhereBeacon.onFix(s, PeerWhereBeacon.Fix(50.45, 30.52, 5, T0), T0)
        s = before.state
        val inboxes = before.out.associate { it.peerId to take(PeerWhereInbox.Inbox(), it.body, T0) }
        assertTrue(inboxes.values.all { it.standing.isNotEmpty() })

        val gone = PeerWhereBeacon.vanish(s, T0 + 10_000L)
        assertEquals(setOf("b", "c"), gone.out.map { it.peerId }.toSet())
        val after = gone.out.associate { o -> o.peerId to take(inboxes.getValue(o.peerId), o.body, T0 + 10_000L) }
        after.forEach { (who, inbox) -> assertNull("у $who лишилась точка", PeerWhereInbox.standingFor(inbox, from, T0 + 10_000L)) }

        val late = before.out.first { it.peerId == "b" }.body
        assertNull(
            "лист, що вийшов до «зникнути», повернув точку",
            PeerWhereInbox.standingFor(take(after.getValue("b"), late, T0 + 20_000L), from, T0 + 20_000L),
        )

        val quiet = gone.state
        val t = T0 + 3_600_000L
        assertTrue(PeerWhereBeacon.onFix(quiet, PeerWhereBeacon.Fix(48.0, 35.0, 5, t), t).out.isEmpty())
        assertTrue(PeerWhereBeacon.onAsk(quiet, "b", t).out.isEmpty())
        assertTrue(PeerWhereBeacon.onTick(quiet, t).out.isEmpty())
        assertNull(PeerWhereBeacon.nextWakeMs(quiet, t))
        assertFalse(PeerWhereBeacon.wantsFix(quiet, t))
        assertNull("власна точка пережила «зникнути»", quiet.fix)
        assertTrue(PeerWhereBeacon.onFix(PeerWhereBeacon.resume(quiet), PeerWhereBeacon.Fix(48.0, 35.0, 5, t), t).out.isEmpty())
    }

    @Test
    fun `недоставлена точка до «стерти» не поїде`() {
        val pos = row("p", "me", "them", area(T0), T0, PeerMessage.State.QUEUED)
        val ask = row("a", "me", "them", PeerWhere.encode(PeerWhere.Ask(T0 + 2_000L))!!, T0 + 2_000L, PeerMessage.State.QUEUED)
        val erase = row("e", "me", "them", PeerWhere.encode(PeerWhere.Erase(T0 + 1_000L))!!, T0 + 1_000L, PeerMessage.State.QUEUED)
        val spent = PeerWhereInbox.spent(listOf(pos, erase, ask), T0 + 3_000L)
        assertEquals(setOf("p"), spent)
    }

    @Test
    fun `близькість — слово з клітинок, не метри`() {
        val (lat, lon) = PeerWhereGrid.centreOf(cell)
        val near = PeerWhereGrid.cellOf(lat + 3_000.0 / 111_320.0, lon, cell.level)
        assertEquals(cell.row + 1, near.row)
        fun standing(c: PeerWhereGrid.Cell) = PeerWhereInbox.Standing(from, PeerWhere.Area(c, T0, T0 + 1, 1), T0 + 1)
        assertEquals(PeerWhereGrid.Nearness.SAME, PeerWhereInbox.nearness(lat, lon, standing(cell)))
        assertEquals(PeerWhereGrid.Nearness.NEAR, PeerWhereInbox.nearness(lat, lon, standing(near)))
        assertEquals(PeerWhereGrid.Nearness.FAR, PeerWhereInbox.nearness(lat + 1.0, lon, standing(cell)))
        val point = PeerWhereInbox.Standing(from, PeerWhere.Point(lat, lon, 5, T0, T0 + 1, 1), T0 + 1)
        assertEquals(PeerWhereGrid.Nearness.SAME, PeerWhereInbox.nearness(lat, lon, point))
        val walk = PeerWhereInbox.Standing(from, PeerWhere.Point(lat + 900.0 / 111_320.0, lon, 5, T0, T0 + 1, 1), T0 + 1)
        assertEquals("девʼятсот метрів — не «далеко»", PeerWhereGrid.Nearness.NEAR, PeerWhereInbox.nearness(lat, lon, walk))
    }

    @Test
    fun `жодна відкрита функція сімейства не повертає число відстані`() {
        val numeric = setOf(
            java.lang.Double.TYPE, java.lang.Float.TYPE, java.lang.Double::class.java, java.lang.Float::class.java,
        )
        val offenders = listOf(PeerWhere::class.java, PeerWhereGrid::class.java, PeerWhereInbox::class.java, PeerWhereBeacon::class.java)
            .flatMap { k ->
                k.declaredMethods
                    .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.returnType in numeric }
                    .map { "${k.simpleName}.${it.name}" }
            }
        assertEquals(emptyList<String>(), offenders)
    }
}
