package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerGroupSettingsTest {

    private val owner = "aaaa1111"
    private val admin = "bbbb2222"
    private val plain = "dddd4444"

    private val group = PeerGroup.Group(id = "g0002", name = "Канал", memberIds = listOf(owner, admin, plain))

    private var clock = 1_000L

    private fun said(from: String, text: String) = PeerMessage(
        id = "m${clock}_$from",
        fromId = from,
        toId = "any",
        body = PeerGroup.encode(group, text),
        sentAtMs = clock++,
    )

    private fun act(from: String, act: PeerGroup.Act) = said(from, PeerGroup.encodeAct(act))

    private fun staffed() = mutableListOf(
        said(owner, "перше"),
        act(owner, PeerGroup.Act.Grant(admin, PeerGroupRoles.Role.ADMIN)),
    )

    private fun stateOf(log: List<PeerMessage>) = PeerGroup.stateOf(log, group.id)!!

    @Test
    fun `defaults are how a branch lived before settings`() {
        val s = stateOf(staffed())
        assertFalse(s.isChannel)
        assertEquals(0, s.slowSeconds)
        assertEquals(PeerGroup.ON, s.setting(PeerGroup.Setting.HISTORY))
        assertEquals("", s.setting(PeerGroup.Setting.LINK))
    }

    @Test
    fun `set round-trips on the wire and bad values are not acts`() {
        val a = PeerGroup.Act.Set(PeerGroup.Setting.SLOW, "30")
        assertEquals(a, PeerGroup.decodeAct(PeerGroup.encodeAct(a)))
        assertNull(PeerGroup.decodeAct("PH-SYS:set:slow:7"))
        assertNull(PeerGroup.decodeAct("PH-SYS:set:nope:on"))
        assertNull(PeerGroup.decodeAct("PH-SYS:set:link:abc"))
        val r = PeerGroup.Act.Rights(admin, setOf(PeerGroupRoles.Right.PIN, PeerGroupRoles.Right.INFO))
        assertEquals(r, PeerGroup.decodeAct(PeerGroup.encodeAct(r)))
        assertNull(PeerGroup.decodeAct("PH-SYS:rights:$admin:pin,fly"))
    }

    @Test
    fun `admin sets slow mode, a member cannot and the receiver ignores it`() {
        val log = staffed()
        log += act(admin, PeerGroup.Act.Set(PeerGroup.Setting.SLOW, "60"))
        assertEquals(60, stateOf(log).slowSeconds)
        val off = PeerGroup.Act.Set(PeerGroup.Setting.SLOW, "0")
        assertEquals(PeerGroupRoles.Why.NEEDS_ADMIN, PeerGroup.refuse(log, group.id, plain, off))
        log += act(plain, off)
        assertEquals(60, stateOf(log).slowSeconds)
    }

    @Test
    fun `only the owner turns a group into a channel`() {
        val log = staffed()
        val kind = PeerGroup.Act.Set(PeerGroup.Setting.KIND, PeerGroup.KIND_CHANNEL)
        assertEquals(PeerGroupRoles.Why.NEEDS_OWNER, PeerGroup.refuse(log, group.id, admin, kind))
        log += act(admin, kind)
        assertFalse(stateOf(log).isChannel)
        log += act(owner, kind)
        assertTrue(stateOf(log).isChannel)
    }

    @Test
    fun `owner narrows an admin's rights and the receiver enforces them`() {
        val log = staffed()
        log += act(owner, PeerGroup.Act.Rights(admin, setOf(PeerGroupRoles.Right.PIN)))
        val roles = stateOf(log).roles
        assertTrue(roles.has(admin, PeerGroupRoles.Right.PIN))
        assertFalse(roles.has(admin, PeerGroupRoles.Right.BAN))
        assertEquals(PeerGroupRoles.Why.NO_RIGHT, PeerGroup.refuse(log, group.id, admin, PeerGroup.Act.Remove(plain)))
        assertEquals(PeerGroupRoles.Why.NO_RIGHT, PeerGroup.refuse(log, group.id, admin, PeerGroup.Act.Rename))
        val link = PeerGroup.Act.Set(PeerGroup.Setting.LINK, "abcdef12")
        assertEquals(PeerGroupRoles.Why.NO_RIGHT, PeerGroup.refuse(log, group.id, admin, link))
        log += act(admin, PeerGroup.Act.Remove(plain))
        assertTrue(plain in stateOf(log).group.memberIds)
        assertFalse(PeerGroupRoles.may(roles, group.memberIds, admin, PeerGroupRoles.Power.REMOVE))
        assertTrue(PeerGroupRoles.may(roles, group.memberIds, admin, PeerGroupRoles.Power.PIN))
    }

    @Test
    fun `only the owner hands out rights, and a new grant resets them`() {
        val log = staffed()
        val narrow = PeerGroup.Act.Rights(plain, emptySet())
        assertEquals(PeerGroupRoles.Why.NOT_A_CHANGE, PeerGroup.refuse(log, group.id, owner, narrow))
        assertEquals(PeerGroupRoles.Why.NEEDS_OWNER, PeerGroup.refuse(log, group.id, admin, PeerGroup.Act.Rights(owner, emptySet())))
        log += act(owner, PeerGroup.Act.Rights(admin, emptySet()))
        assertFalse(stateOf(log).roles.has(admin, PeerGroupRoles.Right.INFO))
        log += act(owner, PeerGroup.Act.Grant(admin, PeerGroupRoles.Role.MODERATOR))
        log += act(owner, PeerGroup.Act.Grant(admin, PeerGroupRoles.Role.ADMIN))
        assertTrue(stateOf(log).roles.has(admin, PeerGroupRoles.Right.INFO))
    }
}
