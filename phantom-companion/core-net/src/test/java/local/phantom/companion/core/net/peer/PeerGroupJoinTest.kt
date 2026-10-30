package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerGroupJoinTest {

    private val owner = "aaaa1111aaaa1111"
    private val admin = "bbbb2222bbbb2222"
    private val joiner = "cccc3333cccc3333"
    private val late = "dddd4444dddd4444"

    private val group = PeerGroup.Group(id = "g00000000001", name = "Двір", memberIds = listOf(owner, admin))

    private val sharer = PeerInvite.Invite(
        peerId = owner,
        name = "Кирило",
        pubKey = "cHViLWtleS1vZi10aGUtb3duZXItcGhvbmUtMzJieXQ",
        dhPubKey = "ZGgta2V5LW9mLXRoZS1vd25lci1waG9uZS0zMmJ5dGU",
        host = "192.168.1.20",
        port = 47100,
    )

    private var clock = 1_000L

    private fun said(from: String, text: String, g: PeerGroup.Group = group) = PeerMessage(
        id = "m${clock}_$from",
        fromId = from,
        toId = "any",
        body = PeerGroup.encode(g, text),
        sentAtMs = clock++,
    )

    private fun act(from: String, act: PeerGroup.Act, g: PeerGroup.Group = group) = said(from, PeerGroup.encodeAct(act), g)

    private fun direct(from: String, to: String, body: String) =
        PeerMessage(id = "d${clock}_$from", fromId = from, toId = to, body = body, sentAtMs = clock++)

    private fun ask(from: String, secret: String) =
        direct(from, owner, PeerGroupJoin.encode(PeerGroupJoin.Frame.Ask(group.id, secret)))

    @Test
    fun `link carries the group, the secret and the sharer without the sharer's address`() {
        val url = PeerGroupJoin.link(group.id, "Abcdefgh23456789", 2, group.name, sharer)
        assertTrue(url.startsWith("https://phantom-os.dev/g/#"))
        assertTrue(PeerGroupJoin.isLink(url))
        val link = PeerGroupJoin.parse(url)!!
        assertEquals(group.id, link.groupId)
        assertEquals("Abcdefgh23456789", link.secret)
        assertEquals(2, link.count)
        assertEquals("Двір", link.name)
        assertEquals(owner, link.sharer.peerId)
        assertEquals("", link.sharer.host)
        assertEquals(0, link.sharer.port)
        assertEquals(link, PeerGroupJoin.parse(url.replace(":", "%3A").replace("https%3A", "https:")))
        assertNull(PeerGroupJoin.parse(url.replace("phantom-os.dev", "phantom-os.dev.evil.io")))
        assertFalse(PeerGroupJoin.isLink(url.replace("/g/", "/i/")))
    }

    @Test
    fun `code joins while valid and is refused after revoke`() {
        val log = mutableListOf(said(owner, "перше"), act(owner, PeerGroup.Act.Set(PeerGroup.Setting.LINK, "Abcdefgh23456789")))

        val first = ask(joiner, "Abcdefgh23456789")
        log += first
        val yes = PeerGroupJoin.reply(log, owner, first)!!
        assertEquals(joiner, yes.add)
        assertEquals(joiner, yes.to)
        assertEquals(PeerGroupJoin.Verdict.JOINED, (PeerGroupJoin.decode(yes.answer) as PeerGroupJoin.Frame.Answer).verdict)
        assertNull(PeerGroup.refuse(log, group.id, owner, PeerGroup.Act.Add(joiner)))
        log += act(owner, PeerGroup.Act.Add(joiner))
        assertTrue(joiner in PeerGroup.stateOf(log, group.id)!!.group.memberIds)

        val link = PeerGroupJoin.Link(group.id, "Abcdefgh23456789", 2, group.name, sharer)
        assertEquals(PeerGroupJoin.Stage.JOINED, PeerGroupJoin.standing(log, joiner, link).stage)

        log += act(owner, PeerGroup.Act.Set(PeerGroup.Setting.LINK, ""))
        val second = ask(late, "Abcdefgh23456789")
        log += second
        val no = PeerGroupJoin.reply(log, owner, second)!!
        assertNull(no.add)
        assertEquals(PeerGroupJoin.Verdict.REVOKED, (PeerGroupJoin.decode(no.answer) as PeerGroupJoin.Frame.Answer).verdict)

        val lateLog = listOf(second, direct(owner, late, no.answer))
        assertEquals(PeerGroupJoin.Standing(PeerGroupJoin.Stage.REFUSED, PeerGroupJoin.Verdict.REVOKED), PeerGroupJoin.standing(lateLog, late, link))
    }

    @Test
    fun `a new link kills the old one`() {
        val log = mutableListOf(
            said(owner, "перше"),
            act(owner, PeerGroup.Act.Set(PeerGroup.Setting.LINK, "Abcdefgh23456789")),
            act(owner, PeerGroup.Act.Set(PeerGroup.Setting.LINK, "Zyxwvuts98765432")),
        )
        assertEquals(PeerGroupJoin.Verdict.REVOKED, PeerGroupJoin.judge(PeerGroup.stateOf(log, group.id), owner, "Abcdefgh23456789", joiner))
        assertEquals(PeerGroupJoin.Verdict.JOINED, PeerGroupJoin.judge(PeerGroup.stateOf(log, group.id), owner, "Zyxwvuts98765432", joiner))
    }

    @Test
    fun `the sharer who lost the invite right cannot let anyone in`() {
        val log = mutableListOf(
            said(owner, "перше"),
            act(owner, PeerGroup.Act.Grant(admin, PeerGroupRoles.Role.ADMIN)),
            act(admin, PeerGroup.Act.Set(PeerGroup.Setting.LINK, "Abcdefgh23456789")),
        )
        val state = { PeerGroup.stateOf(log, group.id) }
        assertEquals(PeerGroupJoin.Verdict.JOINED, PeerGroupJoin.judge(state(), admin, "Abcdefgh23456789", joiner))
        log += act(owner, PeerGroup.Act.Rights(admin, setOf(PeerGroupRoles.Right.PIN)))
        assertEquals(PeerGroupJoin.Verdict.NO_RIGHT, PeerGroupJoin.judge(state(), admin, "Abcdefgh23456789", joiner))
        assertEquals(PeerGroupJoin.Verdict.NO_RIGHT, PeerGroupJoin.judge(null, admin, "Abcdefgh23456789", joiner))
    }

    @Test
    fun `asked until the answer, frames are service and never a line`() {
        val link = PeerGroupJoin.Link(group.id, "Abcdefgh23456789", 2, group.name, sharer)
        assertEquals(PeerGroupJoin.Stage.NONE, PeerGroupJoin.standing(emptyList(), joiner, link).stage)
        val mine = ask(joiner, "Abcdefgh23456789")
        assertEquals(PeerGroupJoin.Stage.ASKED, PeerGroupJoin.standing(listOf(mine), joiner, link).stage)

        val frame = PeerGroupJoin.Frame.Answer(group.id, "Abcdefgh23456789", PeerGroupJoin.Verdict.FULL)
        assertEquals(frame, PeerGroupJoin.decode(PeerGroupJoin.encode(frame)))
        assertNull(PeerGroupJoin.decode("PH-GJN:ask:${group.id}:short"))
        assertEquals(PeerFrames.Kind.GROUP_JOIN, PeerFrames.kindOf(mine.body))
        assertFalse(PeerOutbox.isSpoken(mine))
        assertNotNull(PeerFrames.refuseTyped(mine.body))
        assertEquals(PeerGroupJoin.SECRET_LENGTH, PeerGroupJoin.newSecret().length)
        assertTrue(PeerGroup.Setting.LINK.accepts(PeerGroupJoin.newSecret()))
    }

    private fun verdictOf(answer: String) = (PeerGroupJoin.decode(answer) as PeerGroupJoin.Frame.Answer).verdict

    @Test
    fun `with approval on a join waits, approve makes a member, decline is an honest refusal`() {
        val secret = "Abcdefgh23456789"
        val link = PeerGroupJoin.Link(group.id, secret, 2, group.name, sharer)
        val log = mutableListOf(
            said(owner, "перше"),
            act(owner, PeerGroup.Act.Set(PeerGroup.Setting.LINK, secret)),
            act(owner, PeerGroup.Act.Set(PeerGroup.Setting.APPROVE, PeerGroup.ON)),
        )

        val first = ask(joiner, secret)
        log += first
        val wait = PeerGroupJoin.reply(log, owner, first)!!
        assertNull(wait.add)
        assertEquals(PeerGroupJoin.Verdict.PENDING, verdictOf(wait.answer))
        log += direct(owner, joiner, wait.answer)
        assertEquals(PeerGroupJoin.Standing(PeerGroupJoin.Stage.PENDING), PeerGroupJoin.standing(log, joiner, link))

        val second = ask(late, secret)
        log += second
        log += direct(owner, late, PeerGroupJoin.reply(log, owner, second)!!.answer)
        assertEquals(listOf(joiner, late), PeerGroupJoin.pending(log, owner, group.id).map { it.peerId })
        assertTrue(PeerGroupJoin.pending(log, admin, group.id).isEmpty())

        val yes = PeerGroupJoin.decide(log, owner, group.id, joiner, accept = true)!!
        assertEquals(joiner, yes.add)
        assertEquals(PeerGroupJoin.Verdict.JOINED, verdictOf(yes.answer))
        assertNull(PeerGroup.refuse(log, group.id, owner, PeerGroup.Act.Add(joiner)))
        log += act(owner, PeerGroup.Act.Add(joiner))
        log += direct(owner, joiner, yes.answer)
        assertTrue(joiner in PeerGroup.stateOf(log, group.id)!!.group.memberIds)
        assertEquals(PeerGroupJoin.Stage.JOINED, PeerGroupJoin.standing(log, joiner, link).stage)

        val no = PeerGroupJoin.decide(log, owner, group.id, late, accept = false)!!
        assertNull(no.add)
        assertEquals(late, no.to)
        assertEquals(PeerGroupJoin.Verdict.DECLINED, verdictOf(no.answer))
        log += direct(owner, late, no.answer)
        assertEquals(
            PeerGroupJoin.Standing(PeerGroupJoin.Stage.REFUSED, PeerGroupJoin.Verdict.DECLINED),
            PeerGroupJoin.standing(log, late, link),
        )
        assertFalse(late in PeerGroup.stateOf(log, group.id)!!.group.memberIds)
        assertTrue(PeerGroupJoin.pending(log, owner, group.id).isEmpty())
        assertNull(PeerGroupJoin.decide(log, owner, group.id, late, accept = true))

        val again = ask(late, secret)
        log += again
        log += direct(owner, late, PeerGroupJoin.reply(log, owner, again)!!.answer)
        assertEquals(listOf(late), PeerGroupJoin.pending(log, owner, group.id).map { it.peerId })
    }

    @Test
    fun `an admin who lost the invite right cannot approve a request`() {
        val secret = "Abcdefgh23456789"
        val log = mutableListOf(
            said(owner, "перше"),
            act(owner, PeerGroup.Act.Grant(admin, PeerGroupRoles.Role.ADMIN)),
            act(admin, PeerGroup.Act.Set(PeerGroup.Setting.LINK, secret)),
            act(owner, PeerGroup.Act.Set(PeerGroup.Setting.APPROVE, PeerGroup.ON)),
        )
        val asked = direct(joiner, admin, PeerGroupJoin.encode(PeerGroupJoin.Frame.Ask(group.id, secret)))
        log += asked
        log += direct(admin, joiner, PeerGroupJoin.reply(log, admin, asked)!!.answer)
        assertEquals(listOf(joiner), PeerGroupJoin.pending(log, admin, group.id).map { it.peerId })

        log += act(owner, PeerGroup.Act.Rights(admin, setOf(PeerGroupRoles.Right.PIN)))
        val late = PeerGroupJoin.decide(log, admin, group.id, joiner, accept = true)!!
        assertNull(late.add)
        assertEquals(PeerGroupJoin.Verdict.NO_RIGHT, late.verdict)
    }
}
