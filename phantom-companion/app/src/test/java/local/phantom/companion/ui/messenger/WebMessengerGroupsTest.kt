package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ГРУПИ, ВИВЕДЕНІ В МІСТ.
 *
 * Меш умів групи весь час — ними жив рідний екран, який зносять. Тут
 * перевіряється, що назовні виходить рівно та сама здатність, з тими самими
 * межами: склад засвідчений, але не авторитетний, а доля групового листа —
 * не одна на всіх.
 */
class WebMessengerGroupsTest {

    private val me = "aaaa1111bbbb2222"
    private val marta = "cccc3333dddd4444"
    private val taras = "eeee5555ffff6666"
    private val stranger = "9999aaaa8888bbbb"

    private val group = PeerGroup.Group("g1", "Штаб", listOf(me, marta, taras))

    private fun copy(
        text: String,
        to: String,
        from: String = me,
        at: Long = 1_000L,
        state: PeerMessage.State = PeerMessage.State.QUEUED,
        of: PeerGroup.Group = group,
    ) = PeerMessage(
        id = "$from>$to@$at:${text.hashCode()}",
        fromId = from,
        toId = to,
        body = PeerGroup.encode(of, text),
        sentAtMs = at,
        state = state,
    )

    private fun person(id: String, name: String, key: String = "k-$id") =
        PeerIdentity(id = id, name = name, kind = PeerIdentity.Kind.PERSON, pubKey = "p-$id", dhPubKey = key)

    @Test
    fun `група існує рівно доти, доки існують її листи`() {
        assertEquals("група взялась із порожнього журналу", emptyList<PeerGroup.Group>(), WebMessengerGroups.all(emptyList()))
        val log = listOf(copy("виходимо", marta), copy("виходимо", taras))
        assertEquals(listOf("g1"), WebMessengerGroups.all(log).map { it.id })
        assertEquals("Штаб", WebMessengerGroups.find(log, "g1")?.name)
    }

    /**
     * Одна репліка — один рядок, а не стільки, скільки людей.
     *
     * Без згортання розмова на трьох показувала б кожне речення двічі, і саме
     * так виглядав би «міст, що вміє групи», якби він возив копії як листи.
     */
    @Test
    fun `стрічка згортає віяр до одного сказаного рядка`() {
        val log = listOf(copy("виходимо", marta), copy("виходимо", taras), copy("вже", marta, at = 2_000L))
        val feed = WebMessengerGroups.feed(log, "g1")
        assertEquals(2, feed.size)
        assertEquals(listOf("виходимо", "вже"), feed.map { WebMessengerGroups.said(it.body) })
    }

    /**
     * КОМУ ДІЙШЛО — ПОІМЕННО, А НЕ «2 З 3».
     *
     * У розмові вдвох доля листа одна. Тут їх стільки, скільки людей, і вони
     * різні за побудовою: кожна копія — окремий підписаний лист зі своїм
     * адресатом. Число «1 з 2» чесне, але воно не називає того, хто чекає, — а
     * саме це людина й питає.
     */
    @Test
    fun `кому дійшло називає кожного окремо, а не лише рахує`() {
        val log = listOf(
            copy("виходимо", marta, state = PeerMessage.State.DELIVERED),
            copy("виходимо", taras, state = PeerMessage.State.FAILED),
        )
        val fan = WebMessengerGroups.fan(log, "g1", me, 1_000L)
        assertEquals(
            mapOf(marta to PeerMessage.State.DELIVERED, taras to PeerMessage.State.FAILED),
            fan.associate { it.toId to it.state },
        )
        val counted = PeerGroup.fanState(log, "g1", 1_000L)
        assertEquals(1, counted.delivered)
        assertEquals(1, counted.failed)
    }

    /**
     * Чужий віяр цей телефон бачить однією копією — своєю. Число, зібране з
     * одного свідчення, виглядало б як правда про всіх.
     */
    @Test
    fun `про чужий рядок віяр не вигадується`() {
        val log = listOf(copy("я на місці", me, from = marta, state = PeerMessage.State.DELIVERED))
        assertEquals(1, WebMessengerGroups.fan(log, "g1", marta, 1_000L).size)
        assertTrue(WebMessengerGroups.fan(log, "g1", me, 1_000L).isEmpty())
    }

    /**
     * Склад ЗАСВІДЧЕНИЙ, але не авторитетний.
     *
     * Склад їде в заголовку кожного листа, тож назвати можна й того, кого в
     * ростері немає. Але це слово того, кого ти додав, а не факт про світ:
     * `inRoster=false` — твердження про ТВІЙ список, єдине, що пристрій може
     * перевірити. Прибереш цю ознаку — і перелік почне стверджувати більше,
     * ніж знає.
     */
    @Test
    fun `склад називає й того, кого немає у твоєму списку — але каже про це`() {
        val wider = group.copy(memberIds = group.memberIds + stranger)
        val members = WebMessengerGroups.members(wider, listOf(person(marta, "Марта"), person(taras, "Тарас")), me)
        assertEquals(4, members.size)
        assertTrue("свого ж не впізнав", members.first { it.id == me }.isMe)
        assertFalse("чужого видано за свого", members.first { it.id == stranger }.inRoster)
        assertEquals("Марта", members.first { it.id == marta }.name)
        // Незнайомець не отримує вигаданого імені — лише короткий ключ.
        assertFalse(members.first { it.id == stranger }.name.isBlank())
    }

    /**
     * Додати можна лише того, кому справді доїде.
     *
     * Без ключа копія не запечатається: додавання виглядало б зробленим, поки
     * кожен лист до цієї людини мовчки помирав би в черзі.
     */
    @Test
    fun `додати пропонується лише той, у кого є ключ і кого ще немає`() {
        val roster = listOf(
            person(marta, "Марта"),
            person(stranger, "Олег"),
            person("keyless000000000", "Без ключа", key = ""),
        )
        assertEquals(listOf(stranger), WebMessengerGroups.addable(group, roster).map { it.id })
    }

    @Test
    fun `дія над складом читається як дія, а не як сказане`() {
        val log = listOf(copy(PeerGroup.encodeAct(PeerGroup.Act.Add(stranger)), marta))
        assertEquals(PeerGroup.Act.Add(stranger), WebMessengerGroups.actOf(log.first().body))
        assertEquals(null, WebMessengerGroups.actOf(copy("виходимо", marta).body))
    }

    /**
     * Дорога рядка — одна на весь віяр або жодної, і лише про власний.
     *
     * Міст ставив кожному листу «p2p», і веб малював значок «Доставлено
     * напряму через WebRTC P2P DataChannel» на копії, що лежала у сховку.
     */
    @Test
    fun `дорога групового рядка не говорить за решту копій`() {
        val held = listOf(
            copy("виходимо", marta).copy(heldNonce = "n1"),
            copy("виходимо", taras).copy(heldNonce = "n2"),
        )
        assertEquals(WebMessengerKinds.MAILBOX, WebMessengerGroups.road(held, "g1", held.first(), me))

        val mixed = listOf(held.first(), copy("виходимо", taras, state = PeerMessage.State.DELIVERED))
        assertNull("одна копія сказала за решту", WebMessengerGroups.road(mixed, "g1", mixed.first(), me))

        // Чужий рядок: цей телефон бачить одну копію — свою — і мовчить.
        val theirs = copy("привіт", me, from = marta, state = PeerMessage.State.DELIVERED)
        assertNull(WebMessengerGroups.road(listOf(theirs), "g1", theirs, me))
    }


    @Test
    fun `replyWithin знаходить ціль у тій самій групі і відкидає чужу, а foldedReplyId та replyForRecipient зводять копії віяра`() {
        val m1Marta = copy("перший", marta, at = 1_000L, state = PeerMessage.State.DELIVERED)
        val m1Taras = copy("перший", taras, at = 1_000L, state = PeerMessage.State.QUEUED)
        val otherGroup = PeerGroup.Group("g2", "Інша", listOf(me, marta))
        val m2 = copy("чужий", marta, at = 2_000L, of = otherGroup)
        val log = listOf(m1Marta, m1Taras, m2)
        assertEquals(m1Marta.id, WebMessengerGroups.replyWithin(log, "g1", m1Marta.id))
        assertEquals("", WebMessengerGroups.replyWithin(log, "g1", m2.id))
        assertEquals("", WebMessengerGroups.replyWithin(log, "g1", ""))
        assertEquals(m1Taras.id, PeerGroup.replyForRecipient(log, "g1", m1Marta.id, taras))
        assertEquals(m1Marta.id, PeerGroup.replyForRecipient(log, "g1", m1Taras.id, marta))
        val folded = WebMessengerGroups.feed(log, "g1").single()
        assertEquals(folded.id, WebMessengerGroups.foldedReplyId(log, "g1", m1Marta.id))
        assertEquals(folded.id, WebMessengerGroups.foldedReplyId(log, "g1", m1Taras.id))
    }
}
