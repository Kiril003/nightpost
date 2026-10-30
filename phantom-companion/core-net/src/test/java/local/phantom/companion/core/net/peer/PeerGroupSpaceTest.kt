package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Простір на дроті гілки (11-spaces §10, картка msg-x 05). */
class PeerGroupSpaceTest {

    private val me = "aaaa000000000001"
    private val olia = "bbbb000000000002"
    private val taras = "cccc000000000003"
    private val id = "spc123spc123"

    private fun peer(i: Int) = "f%015d".format(i)

    private fun msg(body: String, fromId: String, at: Long, toId: String = me) =
        PeerMessage(id = "$fromId-$toId-$at-${body.hashCode()}", fromId = fromId, toId = toId, body = body, sentAtMs = at)

    private fun act(group: PeerGroup.Group, a: PeerGroup.Act, fromId: String, at: Long): PeerMessage =
        msg(PeerGroup.encode(PeerGroup.apply(group, fromId, a, group.name, cap = PeerGroup.MAX_SPACE_MEMBERS), PeerGroup.encodeAct(a)), fromId, at)

    @Test
    fun `подія простору їде тілом і читається назад`() {
        val a = PeerGroup.Act.Space("""{"id":"e1","t":"channel.put","actor":"$olia"}""")
        assertEquals(a, PeerGroup.decodeAct(PeerGroup.encodeAct(a)))
        assertNull(PeerGroup.decodeAct("PH-SYS:space:"))
        assertNull(PeerGroup.decodeAct("PH-SYS:space:" + java.util.Base64.getUrlEncoder().encodeToString("ні".toByteArray())))
        assertNull(PeerGroup.decodeAct(PeerGroup.encodeAct(PeerGroup.Act.Space("{" + "x".repeat(PeerGroup.SPACE_MAX)))))
    }

    @Test
    fun `подія простору не міняє гілку й не стає рядком стрічки`() {
        val g = PeerGroup.Group(id, "Клуб", listOf(me, olia, taras))
        val log = listOf(
            msg(PeerGroup.encode(g, "привіт"), me, 1),
            act(g, PeerGroup.Act.Space("""{"id":"e1"}"""), olia, 2),
        )
        val state = PeerGroup.stateOf(log, id)
        assertEquals(listOf(me, olia, taras), state?.group?.memberIds)
        assertTrue(PeerGroup.isSpaceLine(log[1].body))
        assertTrue(PeerGroup.isMachineLine(log[1].body))
        assertEquals("привіт", PeerGroup.lastText(log, id))
        assertEquals(0, PeerGroup.unread(log, id, me, 0))
    }

    @Test
    fun `стара збірка не знає дієслова й мовчить про нього`() {
        assertNull(PeerGroup.decodeAct("PH-SYS:spaces2:e30"))
        assertTrue(PeerGroup.isMachineLine(PeerGroup.encode(PeerGroup.Group(id, "Клуб", listOf(me, olia)), "PH-SYS:spaces2:e30")))
    }

    @Test
    fun `простір тримає 50, класична гілка 32`() {
        val classic = PeerGroup.Group(id, "Гілка", (1..32).map(::peer))
        assertEquals(classic, PeerGroup.apply(classic, peer(1), PeerGroup.Act.Add(peer(33)), classic.name))

        val opener = PeerGroup.Group(id, "Клуб", listOf(me, olia))
        var group = opener
        val log = mutableListOf(msg(PeerGroup.encode(opener, "відкрили"), me, 1))
        log += act(group, PeerGroup.Act.Set(PeerGroup.Setting.KIND, PeerGroup.KIND_SPACE), me, 2)
        (3..51).forEach { i ->
            val add = PeerGroup.Act.Add(peer(i))
            log += act(group, add, me, i.toLong())
            group = PeerGroup.apply(group, me, add, group.name, cap = PeerGroup.MAX_SPACE_MEMBERS)
        }
        val state = PeerGroup.stateOf(log, id)
        assertNotNull(state)
        assertTrue(state!!.isSpace)
        assertEquals(PeerGroup.MAX_SPACE_MEMBERS, state.group.memberIds.size)
        assertEquals(PeerGroupRoles.Why.GROUP_FULL, PeerGroup.refuse(log, id, me, PeerGroup.Act.Add(peer(99))))

        val head50 = PeerGroup.encode(PeerGroup.Group(id, "Клуб", (1..50).map(::peer)), "x")
        val head51 = head50.replaceFirst(",", ",${peer(77)},")
        assertNotNull(PeerGroup.decode(head50))
        assertNull(PeerGroup.decode(head51))
    }

    @Test
    fun `читачі замкненого каналу — єдині адресати`() {
        val g = PeerGroup.Group(id, "Клуб", listOf(me, olia, taras))
        assertEquals(listOf(olia, taras), PeerGroup.fanOut(g, me))
        assertEquals(listOf(olia), PeerGroup.fanOut(g, me, setOf(olia, "stranger")))
        assertEquals(emptyList<String>(), PeerGroup.fanOut(g, me, emptySet()))
    }

    @Test
    fun `рядок каналу несе канал і сказане, набрати його пальцями не можна`() {
        val line = PeerGroup.inChannel("trenery", "завтра о 7")!!
        assertEquals(PeerGroup.ChannelLine("trenery", "завтра о 7"), PeerGroup.channelLine(line))
        assertNull(PeerGroup.inChannel("bad id", "x"))
        assertNull(PeerGroup.channelLine("PH-CHN:\nx"))
        assertEquals(PeerFrames.Kind.CHANNEL, PeerFrames.refuseTyped(line))
        assertFalse(PeerGroup.isChannelLine("канал PH-CHN:x"))
    }

    @Test
    fun `рядок каналу дзвонить сказаним, а не адресою`() {
        val g = PeerGroup.Group(id, "Клуб", listOf(me, olia))
        val body = PeerGroup.encode(g, PeerGroup.inChannel("general", "тренування скасовано")!!)
        val notice = PeerAnnounce.notice(msg(body, olia, 5), visibleChat = null, visibleGroup = null, selfId = me)
        assertEquals("тренування скасовано", notice?.text)
        assertEquals("тренування скасовано", PeerGroup.lastText(listOf(msg(body, olia, 5)), id))
    }

    @Test
    fun `автор події — підписант листа, а не поле JSON, і лише зі складу`() {
        val g = PeerGroup.Group(id, "Клуб", listOf(me, olia, taras))
        val forged = """{"id":"e2","actor":"$me"}"""
        val log = listOf(
            msg(PeerGroup.encode(g, "привіт"), me, 1),
            act(g, PeerGroup.Act.Space(forged), olia, 2),
            msg(PeerGroup.encode(g, PeerGroup.encodeAct(PeerGroup.Act.Space(forged))), olia, 2, toId = taras),
            act(g, PeerGroup.Act.Remove(taras), me, 3),
            act(g.copy(memberIds = listOf(me, olia)), PeerGroup.Act.Space("""{"id":"e3"}"""), taras, 4),
        )
        val events = PeerGroup.stateOf(log, id)!!.spaceEvents
        assertEquals(listOf(PeerGroup.SpaceEvent(olia, 2, forged)), events)
    }

    @Test
    fun `класична гілка з шапкою понад 32 не існує, простір існує й назад не вертається`() {
        val big = PeerGroup.Group(id, "Клуб", listOf(me) + (1..39).map(::peer))
        val opener = msg(PeerGroup.encode(big, "відкрили"), me, 1)
        assertNull(PeerGroup.stateOf(listOf(opener), id))

        val space = listOf(opener, act(big, PeerGroup.Act.Set(PeerGroup.Setting.KIND, PeerGroup.KIND_SPACE), me, 2))
        assertTrue(PeerGroup.stateOf(space, id)!!.isSpace)

        val back = space + act(big, PeerGroup.Act.Set(PeerGroup.Setting.KIND, PeerGroup.KIND_GROUP), me, 3)
        assertEquals(PeerGroup.MAX_SPACE_MEMBERS, PeerGroup.stateOf(back, id)!!.cap)
    }

    @Test
    fun `новачок простору понад 32 бачить простір завдяки картці роду`() {
        val big = PeerGroup.Group(id, "Клуб", listOf(me) + (1..39).map(::peer))
        val newcomer = peer(39)
        val add = msg(PeerGroup.encode(big, PeerGroup.encodeAct(PeerGroup.Act.Add(newcomer))), me, 10, toId = newcomer)
        assertNull(PeerGroup.stateOf(listOf(add), id))

        val card = PeerGroup.newcomerCard(big, PeerGroup.KIND_SPACE)
            .map { msg(PeerGroup.encode(big, PeerGroup.encodeAct(it)), me, 11, toId = newcomer) }
        assertTrue(PeerGroup.stateOf(listOf(add) + card, id)!!.isSpace)
        assertEquals(emptyList<PeerGroup.Act>(), PeerGroup.newcomerCard(PeerGroup.Group(id, "Гілка", listOf(me)), PeerGroup.KIND_GROUP))
    }

    @Test
    fun `склад із заголовка свідчить лише в межах стелі знайомої гілки`() {
        val wide = PeerGroup.Message(PeerGroup.Group(id, "Клуб", (1..33).map(::peer)), "тук")
        val classic = PeerGroup.stateOf(listOf(msg(PeerGroup.encode(PeerGroup.Group(id, "Клуб", listOf(me, olia)), "привіт"), olia, 1)), id)
        assertEquals(PeerGroup.MAX_MEMBERS, classic?.cap)
        assertFalse(PeerGroup.headerFits(wide, classic))
        assertTrue(PeerGroup.headerFits(wide.copy(group = wide.group.copy(memberIds = (1..32).map(::peer))), classic))
        assertTrue("простору 33 — не перебір", PeerGroup.headerFits(wide, classic?.copy(settings = mapOf(PeerGroup.Setting.KIND to PeerGroup.KIND_SPACE))))
        assertTrue("незнайому гілку стримує розбір", PeerGroup.headerFits(wide, null))
    }

    @Test
    fun `зсунутий у минуле заголовок на 33 імені класичної гілки не стирає`() {
        val g = PeerGroup.Group(id, "Гілка", listOf(me, olia, taras))
        val wide = PeerGroup.Group(id, "Гілка", listOf(me, olia, taras) + (4..33).map(::peer))
        val log = listOf(
            msg(PeerGroup.encode(g, "привіт"), olia, 10),
            msg("PH-GRP:$id:${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("Гілка".toByteArray())}:${wide.memberIds.joinToString(",")}\nтук", taras, 1),
        )
        assertEquals(listOf(me, olia, taras), PeerGroup.stateOf(log, id)?.group?.memberIds)
        assertNull("гілка з одних широких заголовків — не гілка", PeerGroup.stateOf(listOf(log[1]), id))
    }
}

