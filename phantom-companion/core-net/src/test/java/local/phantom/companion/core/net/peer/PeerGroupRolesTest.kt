package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ролі перевіряються ПРИЙМАЧЕМ, а не екраном.
 *
 * Кожна перевірка тут ставить одне й те саме питання двічі: чи [PeerGroup.refuse]
 * назвала причину — і чи [PeerGroup.states] справді НЕ застосувала акт. Тест
 * лише на першу половину пропустив би саме ту ваду, заради якої все це
 * написано: суддя, якого ніхто не питає.
 */
class PeerGroupRolesTest {

    private val owner = "aaaa1111"
    private val admin = "bbbb2222"
    private val mod = "cccc3333"
    private val plain = "dddd4444"

    private val group = PeerGroup.Group(
        id = "g0001",
        name = "Бригада",
        memberIds = listOf(owner, admin, mod, plain),
    )

    private var clock = 1_000L

    private fun said(from: String, body: String, group: PeerGroup.Group = this.group) =
        PeerMessage(
            id = "m${clock}_$from",
            fromId = from,
            toId = "any",
            body = body,
            sentAtMs = clock++,
        )

    private fun act(from: String, act: PeerGroup.Act, group: PeerGroup.Group = this.group) =
        said(from, PeerGroup.encode(group, PeerGroup.encodeAct(act)), group)

    /** Журнал, у якому власник уже роздав щаблі. */
    private fun staffed(): MutableList<PeerMessage> = mutableListOf(
        said(owner, PeerGroup.encode(group, "перше слово")),
        act(owner, PeerGroup.Act.Grant(admin, PeerGroupRoles.Role.ADMIN)),
        act(owner, PeerGroup.Act.Grant(mod, PeerGroupRoles.Role.MODERATOR)),
    )

    private fun stateOf(log: List<PeerMessage>) = PeerGroup.stateOf(log, group.id)!!

    // ── хто власник ──────────────────────────────────────────────────────

    @Test
    fun `owner is whoever spoke first in the branch`() {
        val state = stateOf(listOf(said(owner, PeerGroup.encode(group, "привіт"))))
        assertEquals(owner, state.roles.ownerId)
        assertEquals(PeerGroupRoles.Role.OWNER, state.roles.of(owner))
        assertEquals(PeerGroupRoles.Role.MEMBER, state.roles.of(plain))
    }

    @Test
    fun `grants land and are read back`() {
        val state = stateOf(staffed())
        assertEquals(PeerGroupRoles.Role.ADMIN, state.roles.of(admin))
        assertEquals(PeerGroupRoles.Role.MODERATOR, state.roles.of(mod))
        assertEquals(PeerGroupRoles.Role.MEMBER, state.roles.of(plain))
        assertEquals(listOf(owner, admin, mod), state.roles.staff(group.memberIds))
    }

    // ── відмова, названа причиною ────────────────────────────────────────

    @Test
    fun `a plain member cannot rename and the receiver does not apply it`() {
        val log = staffed()
        val renamed = group.copy(name = "Вкрадена назва")
        assertEquals(
            PeerGroupRoles.Why.NEEDS_ADMIN,
            PeerGroup.refuse(log, group.id, plain, PeerGroup.Act.Rename),
        )
        log += act(plain, PeerGroup.Act.Rename, renamed)
        assertEquals("Бригада", stateOf(log).group.name)
    }

    @Test
    fun `an admin renames and the receiver applies it`() {
        val log = staffed()
        assertNull(PeerGroup.refuse(log, group.id, admin, PeerGroup.Act.Rename))
        log += act(admin, PeerGroup.Act.Rename, group.copy(name = "Друга бригада"))
        assertEquals("Друга бригада", stateOf(log).group.name)
    }

    @Test
    fun `a plain member cannot throw anybody out`() {
        val log = staffed()
        assertEquals(
            PeerGroupRoles.Why.NEEDS_MODERATOR,
            PeerGroup.refuse(log, group.id, plain, PeerGroup.Act.Remove(mod)),
        )
        log += act(plain, PeerGroup.Act.Remove(mod))
        assertTrue(mod in stateOf(log).group.memberIds)
    }

    @Test
    fun `a moderator throws out a member but not an admin`() {
        val log = staffed()
        assertEquals(
            PeerGroupRoles.Why.SUBJECT_OUTRANKS,
            PeerGroup.refuse(log, group.id, mod, PeerGroup.Act.Remove(admin)),
        )
        log += act(mod, PeerGroup.Act.Remove(admin))
        assertTrue(admin in stateOf(log).group.memberIds)

        assertNull(PeerGroup.refuse(log, group.id, mod, PeerGroup.Act.Remove(plain)))
        log += act(mod, PeerGroup.Act.Remove(plain))
        assertFalse(plain in stateOf(log).group.memberIds)
    }

    @Test
    fun `an admin cannot mint another admin but the owner can`() {
        val log = staffed()
        assertEquals(
            PeerGroupRoles.Why.ROLE_TOO_HIGH,
            PeerGroup.refuse(log, group.id, admin, PeerGroup.Act.Grant(plain, PeerGroupRoles.Role.ADMIN)),
        )
        log += act(admin, PeerGroup.Act.Grant(plain, PeerGroupRoles.Role.ADMIN))
        assertEquals(PeerGroupRoles.Role.MEMBER, stateOf(log).roles.of(plain))

        log += act(owner, PeerGroup.Act.Grant(plain, PeerGroupRoles.Role.ADMIN))
        assertEquals(PeerGroupRoles.Role.ADMIN, stateOf(log).roles.of(plain))
    }

    @Test
    fun `nobody promotes themselves`() {
        val log = staffed()
        assertEquals(
            PeerGroupRoles.Why.SELF,
            PeerGroup.refuse(log, group.id, plain, PeerGroup.Act.Grant(plain, PeerGroupRoles.Role.ADMIN)),
        )
        log += act(plain, PeerGroup.Act.Grant(plain, PeerGroupRoles.Role.ADMIN))
        assertEquals(PeerGroupRoles.Role.MEMBER, stateOf(log).roles.of(plain))
    }

    @Test
    fun `owner cannot be minted by grant — that is a transfer`() {
        assertEquals(
            PeerGroupRoles.Why.ROLE_TOO_HIGH,
            PeerGroup.refuse(staffed(), group.id, owner, PeerGroup.Act.Grant(admin, PeerGroupRoles.Role.OWNER)),
        )
    }

    @Test
    fun `an outsider has no power at all`() {
        assertEquals(
            PeerGroupRoles.Why.NOT_IN_GROUP,
            PeerGroup.refuse(staffed(), group.id, "zzzz9999", PeerGroup.Act.Rename),
        )
    }

    // ── передача гілки ───────────────────────────────────────────────────

    @Test
    fun `only the owner hands the branch over and keeps admin afterwards`() {
        val log = staffed()
        assertEquals(
            PeerGroupRoles.Why.NEEDS_OWNER,
            PeerGroup.refuse(log, group.id, admin, PeerGroup.Act.Transfer(mod)),
        )
        log += act(admin, PeerGroup.Act.Transfer(mod))
        assertEquals(owner, stateOf(log).roles.ownerId)

        log += act(owner, PeerGroup.Act.Transfer(admin))
        val after = stateOf(log)
        assertEquals(admin, after.roles.ownerId)
        assertEquals(PeerGroupRoles.Role.OWNER, after.roles.of(admin))
        assertEquals(PeerGroupRoles.Role.ADMIN, after.roles.of(owner))
    }

    @Test
    fun `the branch survives its owner leaving — the oldest admin inherits`() {
        val log = staffed()
        log += act(owner, PeerGroup.Act.Leave, group.copy(memberIds = group.memberIds - owner))
        val after = stateOf(log)
        assertFalse(owner in after.group.memberIds)
        assertEquals(admin, after.roles.ownerId)
        // І спадкоємець одразу може те, чого не міг: гілка не лишилась німою.
        assertNull(PeerGroup.refuse(log, group.id, admin, PeerGroup.Act.Transfer(mod)))
    }

    @Test
    fun `with no staff left the first of the roster inherits`() {
        val flat = group.copy(memberIds = listOf(owner, plain))
        val log = mutableListOf(said(owner, PeerGroup.encode(flat, "слово"), flat))
        log += act(owner, PeerGroup.Act.Leave, flat.copy(memberIds = listOf(plain)))
        assertEquals(plain, PeerGroup.stateOf(log, flat.id)!!.roles.ownerId)
    }

    // ── додати може кожен: ціна сумісності ───────────────────────────────

    @Test
    fun `a plain member may still add — nobody vanishes on upgrade`() {
        val log = staffed()
        val newcomer = "eeee5555"
        assertNull(PeerGroup.refuse(log, group.id, plain, PeerGroup.Act.Add(newcomer)))
        log += act(plain, PeerGroup.Act.Add(newcomer))
        assertTrue(newcomer in stateOf(log).group.memberIds)
    }

    // ── закріплене ───────────────────────────────────────────────────────

    @Test
    fun `a moderator pins and unpins, a member does neither`() {
        val log = staffed()
        assertEquals(
            PeerGroupRoles.Why.NEEDS_MODERATOR,
            PeerGroup.refuse(log, group.id, plain, PeerGroup.Act.Pin(owner, 1_000L)),
        )
        log += act(plain, PeerGroup.Act.Pin(owner, 1_000L))
        assertNull(stateOf(log).pinned)

        log += act(mod, PeerGroup.Act.Pin(owner, 1_000L))
        assertEquals(PeerGroup.Pin(owner, 1_000L), stateOf(log).pinned)

        log += act(mod, PeerGroup.Act.Pin(owner, 0L))
        assertNull(stateOf(log).pinned)
    }

    // ── опис і обличчя ───────────────────────────────────────────────────

    @Test
    fun `only an admin changes the face of the branch`() {
        val log = staffed()
        assertEquals(
            PeerGroupRoles.Why.NEEDS_ADMIN,
            PeerGroup.refuse(log, group.id, mod, PeerGroup.Act.About("від модератора")),
        )
        log += act(mod, PeerGroup.Act.About("від модератора"))
        assertEquals("", stateOf(log).group.about)

        log += act(admin, PeerGroup.Act.About("Розвідка, північ"))
        log += act(admin, PeerGroup.Act.Face("data:image/webp;base64,AAAA"))
        val after = stateOf(log)
        assertEquals("Розвідка, північ", after.group.about)
        assertEquals("data:image/webp;base64,AAAA", after.group.face)
    }

    @Test
    fun `an emptied description is a value, not a broken frame`() {
        val log = staffed()
        log += act(admin, PeerGroup.Act.About("щось"))
        log += act(admin, PeerGroup.Act.About(""))
        assertEquals("", stateOf(log).group.about)
    }

    // ── дріт ─────────────────────────────────────────────────────────────

    @Test
    fun `every new verb survives the wire unchanged`() {
        listOf(
            PeerGroup.Act.Grant(admin, PeerGroupRoles.Role.ADMIN),
            PeerGroup.Act.Grant(mod, PeerGroupRoles.Role.MODERATOR),
            PeerGroup.Act.Grant(plain, PeerGroupRoles.Role.MEMBER),
            PeerGroup.Act.Transfer(admin),
            PeerGroup.Act.Pin(owner, 1_726_000_000_000L),
            PeerGroup.Act.Pin(owner, 0L),
            PeerGroup.Act.About("Опис із двокрапкою: і комою, і 🙂"),
            PeerGroup.Act.Face("data:image/webp;base64,UklGRg=="),
        ).forEach { act ->
            assertEquals(act, PeerGroup.decodeAct(PeerGroup.encodeAct(act)))
        }
    }

    @Test
    fun `an unknown rank is not a silent demotion`() {
        assertNull(PeerGroup.decodeAct(PeerGroup.SYS + "grant:$admin:tsarina"))
    }

    @Test
    fun `an over-long description does not travel`() {
        val huge = "я".repeat(PeerGroup.ABOUT_MAX + 1)
        assertNull(PeerGroup.decodeAct(PeerGroup.encodeAct(PeerGroup.Act.About(huge))))
    }

    @Test
    fun `the new verbs stay out of the feed and out of the unread count`() {
        val body = PeerGroup.encode(group, PeerGroup.encodeAct(PeerGroup.Act.Grant(admin, PeerGroupRoles.Role.ADMIN)))
        // Дія над складом — не машинний рядок: її людині ПОКАЗУЮТЬ.
        assertFalse(PeerGroup.isMachineLine(body))
        assertNull(PeerGroup.lastText(listOf(said(owner, body)), group.id))
    }
}
