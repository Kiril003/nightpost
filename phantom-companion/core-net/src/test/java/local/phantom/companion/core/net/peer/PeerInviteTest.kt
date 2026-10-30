package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a second person comes to exist. A code that half-parses adds a peer who
 * can never be reached; a code that parses when it should not adds the wrong
 * one.
 */
class PeerInviteTest {

    private val invite = PeerInvite.Invite(
        peerId = "a1b2c3d4e5f6",
        name = "Марта",
        pubKey = "MCowBQYDK2VwAyEAtestkeytestkeytestkey1234=",
        dhPubKey = "3p7bYQpQVaZ0tGZ0GcQ4Yv1kQwqE8vQGH0oPvOZlXk4",
        host = "192.168.0.14",
        port = 47_331,
    )

    @Test
    fun `an invite survives the round trip`() {
        assertEquals(invite, inviteIn(PeerInvite.encode(invite)))
    }

    @Test
    fun `cyrillic names survive`() {
        val out = inviteIn(PeerInvite.encode(invite.copy(name = "Олег Ґудзь")))
        assertEquals("Олег Ґудзь", out?.name)
    }

    @Test
    fun `a mistyped character is refused, not half-read`() {
        val code = PeerInvite.encode(invite)
        val broken = code.dropLast(7) + "x" + code.takeLast(6)
        assertEquals(PeerInvite.Decoded.NotInvite, PeerInvite.decode(broken))
    }

    @Test
    fun `a code truncated by a messenger is refused`() {
        val code = PeerInvite.encode(invite)
        assertEquals(
            PeerInvite.Decoded.NotInvite,
            PeerInvite.decode(code.take(code.length / 2)),
        )
    }

    @Test
    fun `another app's string is not an invite`() {
        assertEquals(PeerInvite.Decoded.NotInvite, PeerInvite.decode("https://example.com"))
        assertEquals(PeerInvite.Decoded.NotInvite, PeerInvite.decode("PH2:abc:123456"))
        assertEquals(PeerInvite.Decoded.NotInvite, PeerInvite.decode(""))
        assertEquals(PeerInvite.Decoded.NotInvite, PeerInvite.decode(null))
    }

    @Test
    fun `an invite with no signing key is refused`() {
        val bad = PeerInvite.encode(invite.copy(pubKey = ""))
        assertNull("a peer with no key can never be verified", inviteIn(bad))
    }

    @Test
    fun `an invite with no encryption key is refused`() {
        // Nothing could be sent to it, and a contact you cannot write to is a
        // row that lies about being a contact.
        val bad = PeerInvite.encode(invite.copy(dhPubKey = ""))
        assertNull(inviteIn(bad))
    }

    @Test
    fun `an impossible port is refused`() {
        assertNull(inviteIn(PeerInvite.encode(invite.copy(port = 99_999))))
        assertNull(inviteIn(PeerInvite.encode(invite.copy(port = -1))))
    }

    /**
     * Розпаковане тіло коду, а не сам код: адреса лежить у base64, тож
     * `code.contains("158.196.227.74")` було б зеленим і тоді, коли IP їде.
     */
    private fun body(code: String): String =
        String(java.util.Base64.getUrlDecoder().decode(code.split(":")[1]), Charsets.UTF_8)

    @Test
    fun `без живого порту в коді немає адреси — ні IPv4, ні IPv6, ні імені`() {
        // Порт нуль означає, що телефон нічого не слухає. Адреса поруч із ним
        // нікуди не веде, зате називає кожному, кому показали QR, мережу, у
        // якій людина зараз стоїть.
        for (host in listOf("158.196.227.74", "fe80::1c2d:3e4f:5a6b:7c8d", "phantom-a52.local")) {
            val mine = invite.copy(host = host, port = 0)
            val code = PeerInvite.encode(mine)
            assertFalse("адреса в коді: ${body(code)}", body(code).contains(host))
            val inLink = PeerInvite.codeInLink(PeerInvite.link(mine))!!
            assertFalse("адреса в посиланні", body(inLink).contains(host))
            assertEquals("", inviteIn(code)?.host)
            assertNull(PeerInvite.address(mine))
        }
    }

    @Test
    fun `з живим портом адреса лишається на місці`() {
        // Інакше зламали б пряме зʼєднання в спільній мережі — перший лист
        // мусить мати куди піти ще до того, як маяк когось побачив.
        val addressed = invite.copy(host = "192.168.0.14", port = 47_331)
        val code = PeerInvite.encode(addressed)
        assertTrue(body(code).contains("192.168.0.14"))
        assertEquals(addressed, inviteIn(code))
        assertEquals("192.168.0.14:47331", PeerInvite.address(addressed))
        assertEquals("[fe80::1]:47331", PeerInvite.address(addressed.copy(host = "fe80::1")))
    }

    @Test
    fun `безадресний код читається, і ключі з імʼям цілі`() {
        val out = inviteIn(PeerInvite.encode(invite.copy(host = "158.196.227.74", port = 0)))
        assertNotNull("код без адреси мусить читатись — його друкує сам телефон", out)
        assertEquals("", out?.host)
        assertEquals(0, out?.port)
        assertEquals(invite.peerId, out?.peerId)
        assertEquals(invite.pubKey, out?.pubKey)
        assertEquals(invite.dhPubKey, out?.dhPubKey)
        assertEquals("Марта", out?.name)
    }

    @Test
    fun `старий код з IP і нулем читається далі`() {
        // Заморожений рядок форми, яку друкували збірки до 04.09: адреса є,
        // порт нуль. Такі QR уже на руках у людей, і відмова читати їх зробила
        // б їх мертвими — сумісність назад тут не абстракція.
        val out = inviteIn(LEGACY_ADDRESSED_ZERO_PORT)
        assertNotNull("уже роздані коди мусять читатись", out)
        assertEquals("747aa842fd358e5f", out?.peerId)
        assertEquals("PHANTOM-A52", out?.name)
        assertEquals("158.196.227.74", out?.host)
        assertEquals(0, out?.port)
        // Що з нього виходить далі — вже без адреси: перекодований, він її не несе.
        assertFalse(body(PeerInvite.encode(out!!)).contains("158.196.227.74"))
    }

    @Test
    fun `a name cannot smuggle a field separator`() {
        val out = inviteIn(PeerInvite.encode(invite.copy(name = "Марта|fake|key")))
        assertEquals("Марта fake key", out?.name)
    }

    @Test
    fun `a blank name falls back to the key, never to nothing`() {
        val out = inviteIn(PeerInvite.encode(invite.copy(name = "   ")))
        assertEquals(PeerRoster.shortId(invite.peerId), out?.name)
    }

    @Test
    fun `the spoken code is the same on both phones for the same keys`() {
        assertEquals(
            PeerInvite.verificationCode(invite.pubKey, invite.dhPubKey),
            PeerInvite.verificationCode(" " + invite.pubKey + " ", " " + invite.dhPubKey + " "),
        )
    }

    @Test
    fun `a different signing key gives a different spoken code`() {
        assertNotEquals(
            PeerInvite.verificationCode(invite.pubKey, invite.dhPubKey),
            PeerInvite.verificationCode(invite.pubKey.dropLast(1) + "Z", invite.dhPubKey),
        )
    }

    @Test
    fun `swapping only the encryption key changes what the two people say`() {
        // The whole confidentiality guarantee rests here: a middleman who
        // passes the signing key through untouched and substitutes only the
        // X25519 key reads everything, and this is the one line that catches
        // it before anybody taps «звірено».
        assertNotEquals(
            PeerInvite.verificationCode(invite.pubKey, invite.dhPubKey),
            PeerInvite.verificationCode(invite.pubKey, invite.dhPubKey.dropLast(1) + "Z"),
        )
    }

    @Test
    fun `the spoken code is six digits, grouped for saying aloud`() {
        val code = PeerInvite.verificationCode(invite.pubKey, invite.dhPubKey)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
        assertEquals(
            "${code.take(3)} ${code.drop(3)}",
            PeerInvite.spokenCode(invite.pubKey, invite.dhPubKey),
        )
    }

    @Test
    fun `an invite from the old build is named, never parsed`() {
        // A PH1 code carries no encryption key. Turning it into a peer would
        // produce a contact whose messages cannot be sealed — the UI must be
        // able to say «оновіть застосунок», not «поганий код».
        val legacy = "PH1:YTFiMmMzZDQ:abc123"
        assertEquals(PeerInvite.Decoded.LegacyPh1, PeerInvite.decode(legacy))
        assertNull(inviteIn(legacy))
    }

    @Test
    fun `a broken old-format code is still refused as old, not as garbage`() {
        assertEquals(PeerInvite.Decoded.LegacyPh1, PeerInvite.decode("PH1:@@@@:zzzz"))
    }

    private companion object {
        /**
         * Не складений цим кодом — і в цьому вся суть: так друкували збірки до
         * 04.09, і рівно такі рядки лежать у чужих телефонах знімками QR.
         */
        const val LEGACY_ADDRESSED_ZERO_PORT =
            "PH2:NzQ3YWE4NDJmZDM1OGU1ZnxQSEFOVE9NLUE1MnwxMXFZQVlLeENyZlZTLzdUeVdRSE9nN2" +
                "hjdlBhcGlNbHJ3SWFhUGNIVVJvfGhTRHdDWWt3cDFSMGkzM2N0RDczV2cyL09nMG1PQnIw" +
                "NjZTcGpxcWJUbW98MTU4LjE5Ni4yMjcuNzR8MA:e4ef82"
    }
}
