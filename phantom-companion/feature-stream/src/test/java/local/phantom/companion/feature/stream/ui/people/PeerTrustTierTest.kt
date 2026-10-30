package local.phantom.companion.feature.stream.ui.people

import local.phantom.companion.core.net.peer.PeerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дві звірки, а не одна — і жодна не має права на чужу обіцянку.
 *
 * Той самий збіг цифр значить різне залежно від того, яким шляхом прийшов
 * ключ, тож екран мусить казати різне. Найтихіша поразка тут — рядок, звірений
 * до того, як застосунок узагалі почав запамʼятовувати шлях: підвищити його до
 * сильної позначки означало б повторити стару неправду в новому місці.
 */
class PeerTrustTierTest {

    private fun peer(
        source: PeerIdentity.KeySource = PeerIdentity.KeySource.UNKNOWN,
        verifiedAt: Long = 0L,
        keyChangedAt: Long = 0L,
    ) = PeerIdentity(
        id = "a1b2c3d4e5f60718",
        name = "Богдан",
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "KEY-1",
        dhPubKey = "DH-1",
        verifiedAtMs = verifiedAt,
        keyChangedAtMs = keyChangedAt,
        keySource = source,
    )

    @Test
    fun `the camera earns the strong tick and nothing else does`() {
        assertEquals(
            PeerChatFormat.Trust.IN_PERSON,
            PeerChatFormat.trust(peer(PeerIdentity.KeySource.CAMERA, verifiedAt = 500L)),
        )
        for (weak in listOf(
            PeerIdentity.KeySource.REMOTE,
            PeerIdentity.KeySource.SHARED,
            PeerIdentity.KeySource.UNKNOWN,
        )) {
            assertEquals(
                "$weak must never reach the strong tier",
                PeerChatFormat.Trust.MATCHED,
                PeerChatFormat.trust(peer(weak, verifiedAt = 500L)),
            )
        }
    }

    @Test
    fun `a moved key outranks both ticks`() {
        assertEquals(
            PeerChatFormat.Trust.CHANGED,
            PeerChatFormat.trust(
                peer(PeerIdentity.KeySource.CAMERA, verifiedAt = 500L, keyChangedAt = 900L),
            ),
        )
    }

    @Test
    fun `both ticks count as verified, only one counts as proof`() {
        assertTrue(PeerChatFormat.Trust.MATCHED.verified)
        assertTrue(PeerChatFormat.Trust.IN_PERSON.verified)
        assertTrue(!PeerChatFormat.Trust.UNVERIFIED.verified)
        assertTrue(!PeerChatFormat.Trust.CHANGED.verified)
    }

    @Test
    fun `every state gets its own sentence`() {
        assertEquals(
            PeerChatFormat.Why.SCANNED,
            PeerChatFormat.why(peer(PeerIdentity.KeySource.CAMERA)),
        )
        assertEquals(
            PeerChatFormat.Why.HANDED,
            PeerChatFormat.why(peer(PeerIdentity.KeySource.SHARED)),
        )
        assertEquals(
            PeerChatFormat.Why.ELSEWHERE,
            PeerChatFormat.why(peer(PeerIdentity.KeySource.REMOTE)),
        )
        assertEquals(
            PeerChatFormat.Why.ELSEWHERE,
            PeerChatFormat.why(peer(PeerIdentity.KeySource.UNKNOWN)),
        )
        assertEquals(
            PeerChatFormat.Why.IN_PERSON,
            PeerChatFormat.why(peer(PeerIdentity.KeySource.CAMERA, verifiedAt = 500L)),
        )
        assertEquals(
            PeerChatFormat.Why.MATCHED,
            PeerChatFormat.why(peer(PeerIdentity.KeySource.REMOTE, verifiedAt = 500L)),
        )
        assertEquals(
            PeerChatFormat.Why.CHANGED,
            PeerChatFormat.why(peer(PeerIdentity.KeySource.CAMERA, 500L, keyChangedAt = 900L)),
        )
    }

    @Test
    fun `a peer verified before the app recorded the channel says so`() {
        // Не «прийшов листуванням» — цього ніхто не знає. Саме «невідомо звідки»,
        // бо будь-яке інше формулювання було б здогадкою, виданою за факт.
        val legacy = peer(PeerIdentity.KeySource.UNKNOWN, verifiedAt = 500L)
        assertEquals(PeerChatFormat.Why.MATCHED_UNKNOWN, PeerChatFormat.why(legacy))
        assertNotEquals(PeerChatFormat.Why.MATCHED, PeerChatFormat.why(legacy))
        assertEquals(PeerChatFormat.Trust.MATCHED, PeerChatFormat.trust(legacy))
    }
}
