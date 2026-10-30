package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerGroupRoles
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class WebMessengerGroupPostGateTest {

    private val owner = "aaaa1111bbbb2222"
    private val admin = "cccc3333dddd4444"
    private val plain = "eeee5555ffff6666"
    private val group = PeerGroup.Group("g9", "Канал", listOf(owner, admin, plain))

    private fun line(from: String, text: String, at: Long) = PeerMessage(
        id = "$from@$at",
        fromId = from,
        toId = "any",
        body = PeerGroup.encode(group, text),
        sentAtMs = at,
    )

    private fun act(from: String, act: PeerGroup.Act, at: Long) = line(from, PeerGroup.encodeAct(act), at)

    private val base = listOf(
        line(owner, "перше", 1_000L),
        act(owner, PeerGroup.Act.Grant(admin, PeerGroupRoles.Role.ADMIN), 2_000L),
    )

    @Test
    fun `slow mode counts from my own last line and spares admins`() {
        val log = base + act(owner, PeerGroup.Act.Set(PeerGroup.Setting.SLOW, "30"), 3_000L) + line(plain, "привіт", 10_000L)
        assertEquals(WebMessengerGroups.PostGate.Slow(20), WebMessengerGroups.mayPost(log, "g9", plain, 20_500L))
        assertEquals(WebMessengerGroups.PostGate.Open, WebMessengerGroups.mayPost(log, "g9", plain, 40_000L))
        assertEquals(WebMessengerGroups.PostGate.Open, WebMessengerGroups.mayPost(log + line(admin, "a", 20_000L), "g9", admin, 20_001L))
    }

    @Test
    fun `a channel takes posts from admins only`() {
        val log = base + act(owner, PeerGroup.Act.Set(PeerGroup.Setting.KIND, PeerGroup.KIND_CHANNEL), 3_000L)
        assertEquals(WebMessengerGroups.PostGate.AdminsOnly, WebMessengerGroups.mayPost(log, "g9", plain, 5_000L))
        assertEquals(WebMessengerGroups.PostGate.Open, WebMessengerGroups.mayPost(log, "g9", admin, 5_000L))
        assertEquals(WebMessengerGroups.PostGate.Open, WebMessengerGroups.mayPost(log, "g9", owner, 5_000L))
    }
}
