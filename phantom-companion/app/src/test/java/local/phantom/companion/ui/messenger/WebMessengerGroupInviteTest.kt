package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Хто НЕ потрапив у нову групу — названо, а не проковтнуто
 * (`messenger-fix-plan.md` §5.1).
 *
 * До цього `createGroup` мовчки викидав зі складу кожного, до кого немає
 * ключа: організатор запрошував Олену й Петра, група складалась без Петра, а
 * екран казав «створено». Тимчасова група зустрічі живе саме тим, що учасники
 * дістають одне одного, тож мовчазний викид — це зустріч, на яку половину не
 * покликали. Тепер міст повертає викинутих із причиною, і веб називає їх.
 */
class WebMessengerGroupInviteTest {

    private fun peer(id: String, name: String, dh: String = "DH-$id") =
        PeerIdentity(id = id, name = name, kind = PeerIdentity.Kind.PERSON, pubKey = "PUB-$id", dhPubKey = dh)

    private val me = "a1a1a1a1a1a1a1a1"

    @Test
    fun `досяжні йдуть у склад, решта — у викинутих із причиною`() {
        val roster = listOf(
            peer("b2b2b2b2b2b2b2b2", "Олена"),
            peer("c3c3c3c3c3c3c3c3", "Петро", dh = ""),
        )
        val split = WebMessengerGroupInvite.split(
            wanted = listOf("b2b2b2b2b2b2b2b2", "c3c3c3c3c3c3c3c3", "d4d4d4d4d4d4d4d4", "b2b2b2b2b2b2b2b2", me),
            roster = roster,
            me = me,
        )
        assertEquals(listOf("b2b2b2b2b2b2b2b2"), split.members)
        assertEquals(
            listOf(
                WebMessengerGroupInvite.Dropped("c3c3c3c3c3c3c3c3", "Петро", WebMessengerGroupInvite.NO_KEY),
                WebMessengerGroupInvite.Dropped("d4d4d4d4d4d4d4d4", null, WebMessengerGroupInvite.UNKNOWN),
            ),
            split.dropped,
        )
    }

    @Test
    fun `усі досяжні — викинутих немає`() {
        val split = WebMessengerGroupInvite.split(
            wanted = listOf("b2b2b2b2b2b2b2b2"),
            roster = listOf(peer("b2b2b2b2b2b2b2b2", "Олена")),
            me = me,
        )
        assertEquals(listOf("b2b2b2b2b2b2b2b2"), split.members)
        assertEquals(emptyList<WebMessengerGroupInvite.Dropped>(), split.dropped)
    }
}
