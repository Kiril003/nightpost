package local.phantom.companion.feature.stream.ui.people

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дві правди групи, які екран мусить уміти сказати поіменно.
 *
 * «1 з 3» чесне, але нікого не називає, а «в групі є хтось, кого ти не знаєш»
 * не можна вивести з утрати — чужий кадр гине до того, як стане відомо, до
 * якої групи він належав. Обидві відповіді беруться зі складу групи й журналу.
 */
class PeerGroupViewTest {

    private val at = 1_800_000_000_000L
    private val group = PeerGroup.Group("g1", "Виїзд", listOf("me", "marta", "taras"))

    private fun copy(
        id: String,
        toId: String,
        state: PeerMessage.State,
        fromId: String = "me",
        sentAtMs: Long = at,
    ) = PeerMessage(
        id = id,
        fromId = fromId,
        toId = toId,
        body = PeerGroup.encode(group, "виходимо"),
        sentAtMs = sentAtMs,
        state = state,
    )

    @Test
    fun `віяр називає кожного адресата й долю саме його копії`() {
        val detail = PeerChatFormat.fanDetail(
            outbox = listOf(
                copy("1", "marta", PeerMessage.State.DELIVERED),
                copy("2", "taras", PeerMessage.State.FAILED),
            ),
            groupId = "g1",
            fromId = "me",
            sentAtMs = at,
        )
        assertEquals(listOf("marta", "taras"), detail.map { it.toId })
        assertEquals(PeerMessage.State.FAILED, detail.first { it.toId == "taras" }.state)
    }

    @Test
    fun `чужий лист тієї ж миті не потрапляє у власний віяр`() {
        val detail = PeerChatFormat.fanDetail(
            outbox = listOf(
                copy("1", "marta", PeerMessage.State.DELIVERED),
                copy("2", "me", PeerMessage.State.DELIVERED, fromId = "taras"),
            ),
            groupId = "g1",
            fromId = "me",
            sentAtMs = at,
        )
        assertEquals(listOf("marta"), detail.map { it.toId })
    }

    @Test
    fun `рядок, де впала одна копія з двох, видно як частково втрачений`() {
        val outbox = listOf(
            copy("1", "marta", PeerMessage.State.DELIVERED),
            copy("2", "taras", PeerMessage.State.FAILED),
        )
        // Саме той стан, у якому «ще раз» був недосяжний: collapse бере копію
        // з найменшим станом, тож представник рядка ніколи не FAILED.
        val representative = PeerGroup.collapse(outbox, "g1").single()
        assertEquals(PeerMessage.State.DELIVERED, representative.state)
        assertTrue(PeerGroup.fanState(outbox, "g1", at).failed > 0)
    }

    @Test
    fun `учасник, якого немає в реєстрі, названий, а свої — ні`() {
        assertEquals(
            listOf("taras"),
            PeerChatFormat.strangersInGroup(group, setOf("marta"), "me"),
        )
    }

    @Test
    fun `коли всі знайомі, група нічого не приховує`() {
        assertTrue(
            PeerChatFormat.strangersInGroup(group, setOf("marta", "taras"), "me").isEmpty(),
        )
    }
}
