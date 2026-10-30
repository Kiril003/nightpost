package local.phantom.companion.ui.scan

import local.phantom.companion.core.geo.link.MapLink
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerSafety
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.PayLink
import local.phantom.companion.core.solana.SolanaKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeRouteTest {

    private val invite = PeerInvite.Invite(
        peerId = "a1b2c3d4e5f6a7b8",
        name = "Марта",
        pubKey = "o3NKOiXtZDvHEzBuaevdkgoKEPmHCAfUGKfhlxmjevg",
        dhPubKey = "JHxQvxJ0EJ0kOxCkYq2mR5vP1kQ0oZ8mVhq7bT3sXWA",
        host = "",
        port = 0,
    )

    private val pcQr = """{"v":2,"pair_id":"p-12345678","server_pub":"AAAA","exp":4102444800,"nonce":"bm9uY2U=",""" +
        """"endpoints":[{"type":"lan","url":"https://192.168.1.5:8443"}]}"""

    private val addr = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    private val key = SolanaKey.of(addr)

    private fun classify(raw: String): CodeRoute? = CodeRoute.classify(raw, Cluster.DEVNET)

    @Test
    fun `classifies every supported QR format`() {
        val code = PeerInvite.encode(invite)
        assertEquals(CodeRoute.Person(invite), classify(code))
        assertEquals(CodeRoute.Person(invite), classify("phantom://invite/$code"))
        assertEquals(CodeRoute.Person(invite), classify(PeerInvite.webLink(invite)))

        assertEquals(CodeRoute.OldInvite("PH1:abc:def"), classify("PH1:abc:def"))

        val digits48 = "1".repeat(48)
        assertEquals(CodeRoute.SafetyNumber(PeerSafety.qr(digits48)), classify(PeerSafety.qr(digits48)))
        assertEquals(CodeRoute.SafetyNumber(digits48), classify(digits48))

        assertEquals(CodeRoute.Computer(pcQr), classify(pcQr))
        val selfQr = pcQr.replace("\"v\":2", "\"v\":2,\"purpose\":\"self\"")
        assertEquals(CodeRoute.OwnDevice, classify(selfQr))

        assertEquals(CodeRoute.SiteSignIn("NP-AUTH-v1|abc|def", false), classify("NP-AUTH-v1|abc|def"))
        assertEquals(
            CodeRoute.SiteSignIn("NP-AUTH-v1|abc", true),
            classify("https://phantom-os.dev/s?p=NP-AUTH-v1%7Cabc"),
        )

        assertEquals(CodeRoute.Pay(PayLink(key, null, null)), classify(addr))
        assertEquals(CodeRoute.Pay(PayLink(key, Coin.SOL, "1")), classify("solana:$addr?amount=1"))

        val pt = MapLink.Point(48.8566, 2.3522)
        assertEquals(CodeRoute.Place(pt), classify(MapLink.own(pt)))
        assertEquals(CodeRoute.Place(pt), classify("geo:48.8566,2.3522"))
        assertEquals(CodeRoute.Place(pt), classify(MapLink.osm(pt)))
    }

    @Test
    fun `unknown codes are not ours, so the camera keeps scanning`() {
        listOf(
            "",
            "   ",
            "12345678",
            "hello",
            "x".repeat(5000),
            "https://example.com/menu",
            "WIFI:S:home;T:WPA;P:secret;;",
            "BEGIN:VCARD\nFN:Марта\nEND:VCARD",
            "solana:$addr?spl-token=11111111111111111111111111111112",
            "solana:not-a-key",
            "{\"menu\":[1,2]}",
        ).forEach { assertNull("«${it.take(40)}» не наш код — камера шукає далі", classify(it)) }
    }

    @Test
    fun `classifier checks run in the scanner-formats section 6 order`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val code = File(root, "app/src/main/java/local/phantom/companion/ui/scan/CodeRoute.kt").readText()
        val body = code.substringAfter("fun classify(").substringBefore("private fun signInLink")
        val order = listOf(
            "WebAuthQr.PREFIX",
            "signInLink(t)",
            "startsWith(PayLink.SCHEME)",
            "PeerSafety.read(t)",
            "startsWith(\"{\")",
            "PeerInvite.decode(",
            "MapLink.parse(t)",
            "PayLink.parse(t, cluster)",
        )
        val at = order.dropLast(1).map { body.indexOf(it) } + body.lastIndexOf(order.last())
        order.zip(at).forEach { (k, i) -> assertTrue("у classify немає «$k»", i >= 0) }
        assertEquals("порядок перевірок не той, що в §6", at.sorted(), at)
    }

    @Test
    fun `matchSafety finds the matching person or returns null`() {
        val mine = "1".repeat(24)
        val people = listOf("p1" to "2".repeat(24), "p2" to "3".repeat(24))
        assertEquals("p2", matchSafety(PeerSafety.qr("3".repeat(24) + mine), mine, people))
        assertNull(matchSafety(PeerSafety.qr("9".repeat(24) + mine), mine, people))
    }
}
