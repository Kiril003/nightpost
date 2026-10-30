package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a code actually travels. It is pasted into a messenger, forwarded,
 * wrapped in a sentence, and tapped — and every one of those has mangled it
 * in some way before it reaches the decoder.
 */
class PeerInviteLinkTest {

    private val invite = PeerInvite.Invite(
        peerId = "a1b2c3d4e5f6a7b8",
        name = "Марта",
        pubKey = "o3NKOiXtZDvHEzBuaevdkgoKEPmHCAfUGKfhlxmjevg",
        dhPubKey = "JHxQvxJ0EJ0kOxCkYq2mR5vP1kQ0oZ8mVhq7bT3sXWA",
        host = "192.168.0.14",
        port = 47_331,
    )

    @Test
    fun `a code survives being sent and read back`() {
        assertEquals(invite, inviteIn(PeerInvite.encode(invite)))
    }

    @Test
    fun `a messenger's trailing whitespace does not break it`() {
        val code = PeerInvite.encode(invite)
        assertNotNull(inviteIn("  $code \n"))
    }

    @Test
    fun `the same invite always encodes identically`() {
        // A code that changes between screens is a code two people cannot
        // compare, and comparing is the whole security model.
        assertEquals(PeerInvite.encode(invite), PeerInvite.encode(invite))
    }

    @Test
    fun `a code from another PHANTOM version is refused, not misread`() {
        val code = PeerInvite.encode(invite)
        assertEquals(
            PeerInvite.Decoded.NotInvite,
            PeerInvite.decode(code.replaceFirst("PH2", "PH9")),
        )
    }

    @Test
    fun `two different people never share a spoken code`() {
        val other = invite.copy(
            peerId = "ffffffffffffffff",
            pubKey = "59laRL0FBA7VWTriprh3J36GE9acY4KjTWKsQd29WHc",
        )
        assertTrue(
            PeerInvite.verificationCode(invite.pubKey, invite.dhPubKey) !=
                PeerInvite.verificationCode(other.pubKey, other.dhPubKey),
        )
    }

    @Test
    fun `an invite carrying no address still adds the person`() {
        // Discovery finds them later; a peer with no host is not an error.
        val roaming = invite.copy(host = "")
        val out = inviteIn(PeerInvite.encode(roaming))
        assertEquals("", out?.host)
        assertEquals(invite.peerId, out?.peerId)
    }

    @Test
    fun `a tappable link carries the same code back`() {
        // Обробник phantom:// був у застосунку від початку; посилання не робив
        // ніхто. Кільце має замикатись саме тим шляхом, яким його відкриють.
        val url = PeerInvite.link(invite)
        assertEquals(invite, inviteIn(PeerInvite.codeInLink(url)))
    }

    @Test
    fun `the link looks like what the manifest already listens for`() {
        val url = PeerInvite.link(invite)
        assertTrue(url.startsWith("phantom://invite/"))
        // MainActivity бере Uri.path і зрізає провідний слеш — той самий шлях.
        val path = java.net.URI(url).path ?: java.net.URI(url).schemeSpecificPart
        assertEquals(invite, inviteIn(path.trimStart('/')))
    }

    @Test
    fun `somebody else's link is not ours to open`() {
        assertEquals(null, PeerInvite.codeInLink("https://example.org/invite/PH2:x:y"))
        assertEquals(null, PeerInvite.codeInLink("phantom://peer/a1b2"))
        assertEquals(null, PeerInvite.codeInLink("phantom://invite/"))
        assertEquals(null, PeerInvite.codeInLink(null))
    }

    @Test
    fun `the site link carries the code in the hash and comes back whole`() {
        val url = PeerInvite.webLink(invite)
        assertTrue(url, url.startsWith("https://phantom-os.dev/i/#PH2:"))
        assertEquals(invite, PeerInvite.fromLink(url))
        assertEquals(invite, PeerInvite.fromLink(url.replace("/i/#", "/i#")))
        assertEquals(invite, PeerInvite.fromLink(url.replace(":", "%3A").replaceFirst("https%3A", "https:")))
        assertEquals(invite, PeerInvite.fromLink(PeerInvite.link(invite)))
    }

    @Test
    fun `a site link anywhere but our host and invite path is not ours`() {
        val code = PeerInvite.encode(invite)
        listOf(
            "https://evil.example/i/#$code",
            "https://phantom-os.dev.evil.example/i/#$code",
            "https://evil.example/phantom-os.dev/i/#$code",
            "https://user@phantom-os.dev/i/#$code",
            "https://phantom-os.dev:8443/i/#$code",
            "http://phantom-os.dev/i/#$code",
            "https://phantom-os.dev/x/#$code",
            "https://phantom-os.dev/index/#$code",
            "https://phantom-os.dev/i/$code",
            "https://phantom-os.dev/i/?c=$code",
            "https://phantom-os.dev/i/#",
        ).forEach { url ->
            assertEquals(url, null, PeerInvite.codeInLink(url))
            assertEquals(url, null, PeerInvite.fromLink(url))
        }
    }

    @Test
    fun `the code is found inside a message that carries the site link`() {
        val message = "Марта кличе тебе в PHANTOM: ${PeerInvite.webLink(invite)}\nабо код: ${PeerInvite.encode(invite)}"
        assertEquals(invite, inviteIn(PeerInvite.findInText(message)))
    }

    @Test
    fun `the code is found inside a whole introduction message`() {
        // Ніхто не вирізає код ножицями: друг ділиться або вставляє ЦІЛЕ
        // повідомлення з телеграма — з реченнями довкола посилання.
        val message = "Марта кличе тебе в PHANTOM.\n\nЗапрошення:\n" +
            PeerInvite.link(invite) + "\n\nПравда наперед: доки не встановиш — не поїде."
        assertEquals(invite, inviteIn(PeerInvite.findInText(message)))
    }

    @Test
    fun `a bare pasted code is found as itself`() {
        assertEquals(invite, inviteIn(PeerInvite.findInText("  ${PeerInvite.encode(invite)} ")))
    }

    @Test
    fun `a truncated code inside a message stays invisible`() {
        // findInText мовчить про обірване — інакше «код неповний» ніхто б не
        // сказав, і людина слала б обірваний код знову й знову.
        val cut = PeerInvite.encode(invite).dropLast(10)
        assertEquals(null, PeerInvite.findInText("глянь: phantom://invite/$cut"))
    }

    @Test
    fun `ordinary text carries no invite`() {
        assertEquals(null, PeerInvite.findInText("зустрінемось о 19:30 біля входу"))
        assertEquals(null, PeerInvite.findInText(null))
        assertEquals(null, PeerInvite.findInText("   "))
    }

    @Test
    fun `the decoded peer is exactly what the roster stores`() {
        val out = inviteIn(PeerInvite.encode(invite))!!
        val roster = PeerRoster.upsert(
            emptyList(),
            PeerIdentity(
                id = out.peerId,
                name = out.name,
                kind = PeerIdentity.Kind.PERSON,
                pubKey = out.pubKey,
                dhPubKey = out.dhPubKey,
                transport = out.host,
            ),
        )
        assertEquals(1, roster.size)
        assertEquals(invite.pubKey, roster.single().pubKey)
        assertEquals(invite.dhPubKey, roster.single().dhPubKey)
        assertEquals("Марта", roster.single().name)
    }
}
