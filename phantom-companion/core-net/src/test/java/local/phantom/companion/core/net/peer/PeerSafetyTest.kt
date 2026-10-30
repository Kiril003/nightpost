package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Число, яке двоє порівнюють, — і чому його не підібрати.
 *
 * Шість цифр перебирались за мільйон спроб (PeerVerificationTest робить це
 * щоразу). Тут те саме питання ставиться довгому відбитку — і числу розмови,
 * складеному з двох відбитків ПОРУЧ, а не з хешу пари: хеш пари підбирається
 * зустрічно з обох кінців, і це вдвічі коротший перебір, а не вдвічі довший.
 */
class PeerSafetyTest {

    private val myPub = "ED-ME"
    private val myDh = "X-ME"
    private val herPub = "ED-HER"
    private val herDh = "X-HER"

    private fun fp(pub: String, dh: String) = PeerInvite.fingerprint(pub, dh)

    @Test
    fun `a fingerprint is twenty-four digits and follows only the keys`() {
        val fp = fp(myPub, myDh)
        assertEquals(PeerInvite.FINGERPRINT_DIGITS, fp.length)
        assertTrue(fp.all { it.isDigit() })
        assertEquals("пробіли навколо ключа — не інший ключ", fp, fp(" $myPub ", " $myDh "))
        assertNotEquals(fp, fp("ED-OTHER", myDh))
        assertNotEquals("ключ обміну теж входить", fp, fp(myPub, "X-OTHER"))
    }

    @Test
    fun `the number is the same on both phones`() {
        assertEquals(
            PeerSafety.number(myPub, myDh, herPub, herDh),
            PeerSafety.number(herPub, herDh, myPub, myDh),
        )
    }

    @Test
    fun `the number is two fingerprints side by side, never a hash of the pair`() {
        val n = PeerSafety.number(myPub, myDh, herPub, herDh)
        assertEquals(PeerSafety.DIGITS, n.length)
        val halves = setOf(n.take(PeerInvite.FINGERPRINT_DIGITS), n.drop(PeerInvite.FINGERPRINT_DIGITS))
        assertEquals(
            "кожна половина мусить дорівнювати чиємусь справжньому ключу окремо",
            setOf(fp(myPub, myDh), fp(herPub, herDh)),
            halves,
        )
    }

    @Test
    fun `a swapped key on either phone changes the number`() {
        val real = PeerSafety.number(myPub, myDh, herPub, herDh)
        assertNotEquals(real, PeerSafety.number(myPub, myDh, herPub, "X-MITM"))
        assertNotEquals(real, PeerSafety.number(myPub, "X-MITM", herPub, herDh))
        assertNotEquals(real, PeerSafety.number(myPub, myDh, "ED-MITM", herDh))
    }

    @Test
    fun `the QR, the pretty form and the three lines all read back to the same digits`() {
        val n = PeerSafety.number(myPub, myDh, herPub, herDh)
        assertEquals(n, PeerSafety.read(PeerSafety.qr(n)))
        assertEquals(n, PeerSafety.read(PeerSafety.pretty(n)))
        assertEquals(n, PeerSafety.read(PeerSafety.lines(n).joinToString("\n")))
        assertEquals(3, PeerSafety.lines(n).size)
        assertTrue(PeerSafety.lines(n).all { it.length == 19 })
    }

    @Test
    fun `an invite, a short code or a sentence is not a number that merely differs`() {
        val n = PeerSafety.number(myPub, myDh, herPub, herDh)
        val invite = PeerInvite.Invite("id", "Марта", myPub, myDh, "10.0.0.2", 5555)
        assertNull(PeerSafety.read(PeerInvite.encode(invite)))
        assertNull(PeerSafety.read(PeerInvite.spokenCode(myPub, myDh)))
        assertNull(PeerSafety.read(PeerSafety.QR_PREFIX))
        assertNull(PeerSafety.read(n.dropLast(1)))
        assertNull(PeerSafety.read(n + "1"))
        assertNull(PeerSafety.read("звірте число $n"))
        assertNull(PeerSafety.read(null))
    }

    @Test
    fun `the comparison names the phone that holds the wrong key`() {
        val mine = fp(myPub, myDh)
        val hers = fp(herPub, herDh)
        // Her screen shows her real key and her copy of mine; I compare against
        // my real key and MY copy of hers.
        assertEquals(
            PeerSafety.Outcome.MATCH,
            PeerSafety.compare(PeerSafety.qr(PeerSafety.number(herPub, herDh, myPub, myDh)), mine, hers),
        )
        assertEquals(
            "під її іменем у мене чужий ключ",
            PeerSafety.Outcome.THEIRS_DIFFERS,
            PeerSafety.compare(
                PeerSafety.number(herPub, herDh, myPub, myDh),
                mine,
                fp(herPub, "X-MITM-ON-MY-PHONE"),
            ),
        )
        assertEquals(
            "під моїм іменем у неї чужий ключ",
            PeerSafety.Outcome.MINE_DIFFERS,
            PeerSafety.compare(
                PeerSafety.number(herPub, herDh, myPub, "X-MITM-ON-HER-PHONE"),
                mine,
                hers,
            ),
        )
        assertEquals(
            PeerSafety.Outcome.BOTH_DIFFER,
            PeerSafety.compare(
                PeerSafety.number(herPub, "X-MITM-1", myPub, "X-MITM-2"),
                mine,
                hers,
            ),
        )
        assertEquals(PeerSafety.Outcome.NOT_A_NUMBER, PeerSafety.compare("PH2:abc:123456", mine, hers))
        assertTrue(PeerSafety.Outcome.THEIRS_DIFFERS.mismatch)
        assertTrue(!PeerSafety.Outcome.MATCH.mismatch && !PeerSafety.Outcome.NOT_A_NUMBER.mismatch)
    }

    /**
     * The mirror of the six-digit grind: the same attacker, the same budget
     * that beat the short code with room to spare, and nothing to show for it.
     * Not a proof of 2^80 — a proof that the budget which worked no longer does.
     */
    @Test
    fun `a substituted encryption key cannot be ground to the fingerprint`() {
        val signing = "ED25519-REAL"
        val target = PeerInvite.fingerprint(signing, "X25519-REAL")
        var tries = 0L
        while (tries < 1_500_000L) {
            if (PeerInvite.fingerprint(signing, "X25519-MITM-$tries") == target) {
                throw AssertionError("відбиток підібрано за $tries спроб — довжина зрадила")
            }
            tries++
        }
    }
}
