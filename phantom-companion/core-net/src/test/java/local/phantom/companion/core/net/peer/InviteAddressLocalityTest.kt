package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Аркуш запрошення підписував `host:port` як «Так вас видно ззовні».
 * На кадрі 4 з апарата там стояло `10.0.2.15:38211` — NAT емулятора, за яким
 * ззовні нікого. Підпис робив заяву, якої поле не витримує.
 *
 * Рішення про слова лишається поверхні; сюди переїхав ФАКТ, на якому вона
 * тепер тримається. Тест міряє саме його — не текст.
 */
class InviteAddressLocalityTest {

    private fun at(host: String) = PeerInvite.Invite(
        peerId = "a1b2c3d4e5f6a7b8",
        name = "Марта",
        pubKey = "o3NKOiXtZDvHEzBuaevdkgoKEPmHCAfUGKfhlxmjevg",
        dhPubKey = "JHxQvxJ0EJ0kOxCkYq2mR5vP1kQ0oZ8mVhq7bT3sXWA",
        host = host,
        port = 38_211,
    )

    @Test
    fun `приватні діапазони названо приватними`() {
        for (h in listOf(
            "10.0.2.15", "192.168.1.7", "172.16.0.1", "172.31.255.254",
            "127.0.0.1", "169.254.10.1", "100.64.0.1", "100.127.255.255",
            "localhost", "phantom.local", "fd00::1", "::1",
        )) {
            assertEquals("$h мусить бути локальною", true, PeerInvite.isLocalAddress(at(h)))
        }
    }

    @Test
    fun `публічні адреси не видаються за локальні`() {
        for (h in listOf("140.238.221.7", "8.8.8.8", "172.32.0.1", "192.169.0.1", "100.128.0.1")) {
            assertEquals("$h мусить бути публічною", false, PeerInvite.isLocalAddress(at(h)))
        }
    }

    @Test
    fun `без адреси судити нема про що`() {
        assertNull(PeerInvite.isLocalAddress(at("")))
        assertNull("імʼя — не адреса, і вгадувати тут не можна", PeerInvite.isLocalAddress(at("node.example.org")))
    }
}
