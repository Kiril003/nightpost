package local.phantom.companion.self

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.route.BrainRequestSigner
import local.phantom.companion.core.ai.route.NightpostBrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrainLiveTokenTest {

    private class FakeSigner : BrainRequestSigner {
        var lastMessage: String? = null
        override val publicKeyB64: String = "pub-key-b64"
        override fun newNonce(): String = "nonce-hex-01"
        override fun nowMs(): Long = 1700000000000L
        override fun sign(message: ByteArray): String {
            lastMessage = message.toString(Charsets.UTF_8)
            return "sig-b64"
        }
    }

    @Test
    fun `signs live over the exact body`() = runTest {
        val signer = FakeSigner()
        var calledUrl = ""
        var calledHeaders = emptyMap<String, String>()
        var calledBody = ""
        val token = BrainLiveToken(
            base = "https://nightpost.local/",
            signer = signer,
            allowed = { true },
            post = { url, headers, body ->
                calledUrl = url
                calledHeaders = headers
                calledBody = body
                200 to """{"token":"t"}"""
            },
        )
        val res = token.fetch()
        assertEquals(200 to """{"token":"t"}""", res)
        assertEquals("https://nightpost.local/api/brain/live-token", calledUrl)
        assertEquals("{}", calledBody)
        val expectedHash = NightpostBrainRoute.sha256Hex("{}".toByteArray(Charsets.UTF_8))
        assertEquals(
            "NP-BRAIN-v1|pub-key-b64|nonce-hex-01|1700000000000|live|$expectedHash",
            signer.lastMessage,
        )
        assertEquals("pub-key-b64", calledHeaders[NightpostBrainRoute.KEY_HEADER])
        assertEquals("1700000000000", calledHeaders[NightpostBrainRoute.TS_HEADER])
        assertEquals("nonce-hex-01", calledHeaders[NightpostBrainRoute.NONCE_HEADER])
        assertEquals("sig-b64", calledHeaders[NightpostBrainRoute.SIG_HEADER])
    }

    @Test
    fun `not allowed means no call`() = runTest {
        var count = 0
        val token = BrainLiveToken(
            base = "https://nightpost.local",
            signer = FakeSigner(),
            allowed = { false },
            post = { _, _, _ -> count++; 200 to "{}" },
        )
        assertNull(token.fetch())
        assertEquals(0, count)
    }

    @Test
    fun `blank base means no call`() = runTest {
        var count = 0
        val token = BrainLiveToken(
            base = "   ",
            signer = FakeSigner(),
            allowed = { true },
            post = { _, _, _ -> count++; 200 to "{}" },
        )
        assertNull(token.fetch())
        assertEquals(0, count)
    }
}
