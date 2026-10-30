package local.phantom.companion.core.net.relay

import local.phantom.companion.core.net.pair.PairingQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeRoutesTest {

    private fun qr(vararg endpoints: PairingQr.Endpoint) = PairingQr(
        v = 2,
        pairId = "pair-1",
        serverPubB64 = "AAAA",
        serverCertSha256 = "ab12",
        exp = Long.MAX_VALUE,
        nonce = "bm9uY2U=",
        endpoints = endpoints.toList(),
    )

    @Test fun `the network at hand comes before the way around`() {
        val routes = NodeRoutes { _, _ -> "http://127.0.0.1:7000" }
        val resolved = routes.resolveAll(
            qr(
                PairingQr.Endpoint(type = "relay", url = "https://relay.example", nodeId = "n1"),
                PairingQr.Endpoint(type = "lan", url = "https://192.168.0.5:8000"),
            )
        )
        assertEquals(listOf("https://192.168.0.5:8000", "http://127.0.0.1:7000"), resolved)
    }

    @Test fun `a relay without a node id leads nowhere`() {
        val routes = NodeRoutes { _, _ -> "http://127.0.0.1:7000" }
        assertNull(routes.resolve(PairingQr.Endpoint(type = "relay", url = "https://relay.example")))
        assertEquals(
            listOf("https://192.168.0.5:8000"),
            routes.resolveAll(
                qr(
                    PairingQr.Endpoint(type = "lan", url = "https://192.168.0.5:8000"),
                    PairingQr.Endpoint(type = "relay", url = "https://relay.example"),
                )
            ),
        )
    }

    @Test fun `a v1 code still yields its plain addresses`() {
        val legacy = PairingQr(
            v = 1,
            pairId = "pair-1",
            serverPubB64 = "AAAA",
            serverCertSha256 = "dev-no-pin",
            exp = Long.MAX_VALUE,
            nonce = "bm9uY2U=",
            host = "phantom.local",
            ip = "192.168.0.5",
            port = 8000,
        )
        assertEquals(
            listOf("http://phantom.local:8000", "http://192.168.0.5:8000"),
            NodeRoutes().resolveAll(legacy),
        )
    }

    @Test fun `a tunnel that refuses to open is skipped, not fatal`() {
        val routes = NodeRoutes { _, _ -> null }
        assertEquals(
            listOf("https://192.168.0.5:8000"),
            routes.resolveAll(
                qr(
                    PairingQr.Endpoint(type = "lan", url = "https://192.168.0.5:8000"),
                    PairingQr.Endpoint(type = "relay", url = "https://relay.example", nodeId = "n1"),
                )
            ),
        )
    }

    @Test fun `the meeting point address becomes a socket address`() {
        assertEquals("wss://relay.example", RelayTunnel.socketScheme("https://relay.example/"))
        assertEquals("ws://127.0.0.1:8787", RelayTunnel.socketScheme("http://127.0.0.1:8787"))
        assertEquals(
            "wss://relay.example/relay/client?node_id=abc",
            RelayTunnel.clientUrl("https://relay.example", "abc"),
        )
    }

    @Test fun `the local end really listens and lets go on stop`() {
        val tunnel = RelayTunnel("https://relay.example", "abc")
        val url = tunnel.start()
        try {
            assertTrue("очікував локальну адресу, отримав $url", url.startsWith("https://127.0.0.1:"))
            assertEquals("повторний старт мусить віддати ту саму трубу", url, tunnel.start())
            val port = url.substringAfterLast(':').toInt()
            java.net.Socket("127.0.0.1", port).close()
        } finally {
            tunnel.stop()
        }
        assertNull(tunnel.localBaseUrl())
    }

    @Test fun `a blank node id is refused outright`() {
        var threw = false
        try {
            RelayTunnel("https://relay.example", "  ").start()
        } catch (t: IllegalArgumentException) {
            threw = true
        }
        assertTrue("порожній node_id мусить впасти одразу", threw)
    }
}
