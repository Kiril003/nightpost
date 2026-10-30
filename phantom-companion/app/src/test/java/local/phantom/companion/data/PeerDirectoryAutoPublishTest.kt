package local.phantom.companion.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.peer.PeerIntro
import local.phantom.companion.core.net.peer.PeerInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeerDirectoryAutoPublishTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val keys = Ed25519.generateKeypair()
    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().withoutPadding().encodeToString(b)
    private val card = PeerInvite.Invite(
        peerId = PeerIntro.fingerprint(b64(keys.publicKey)),
        name = "Марта",
        pubKey = b64(keys.publicKey),
        dhPubKey = b64(X25519.generateKeypair().publicKey),
        host = "192.168.1.9",
        port = 47_331,
    )

    private fun engine() = MockEngine { request ->
        when (request.method) {
            HttpMethod.Post -> respond("""{"handle_hash":"aa","exp":4102444800}""", HttpStatusCode.OK, json)
            else -> respond("", HttpStatusCode.OK, json)
        }
    }

    private fun store(engine: MockEngine): PeerDirectoryStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("phantom_directory_handle_v1", Context.MODE_PRIVATE).edit().clear().commit()
        return PeerDirectoryStore(
            context = ctx,
            baseUrl = "https://example.invalid",
            myInvite = { card },
            signingKey = { keys.privateKey },
            http = HttpClient(engine) {
                install(ContentNegotiation) { json() }
                expectSuccess = false
            },
        )
    }

    @Test
    fun `a nickname that was never published is published once on start`() = runBlocking {
        val engine = engine()
        val s = store(engine)
        assertNull(s.handle.value)

        val out = s.autoPublish("kyrylo_m1")

        assertTrue(out is PeerDirectoryStore.Outcome.Published)
        assertEquals("kyrylo_m1", s.handle.value)
        assertEquals(listOf(HttpMethod.Post), engine.requestHistory.map { it.method })
    }

    @Test
    fun `a name that is not a nickname is not published`() = runBlocking {
        val engine = engine()
        val s = store(engine)

        assertNull(s.autoPublish("Кирило"))
        assertNull(s.autoPublish("kyrylo"))
        assertTrue("нічого не пішло в мережу", engine.requestHistory.isEmpty())
    }

    @Test
    fun `an explicit withdraw is respected by the next start`() = runBlocking {
        val engine = engine()
        val s = store(engine)
        s.autoPublish("kyrylo_m1")
        s.withdraw()
        val before = engine.requestHistory.size

        assertNull(s.handle.value)
        assertTrue(s.optedOut)
        assertNull(s.autoPublish("kyrylo_m1"))
        assertEquals("після «Ніхто» нік не повертається", before, engine.requestHistory.size)
    }

    @Test
    fun `a withdraw that did not reach the server is retried and never re-published`() {
        val step = PeerDirectoryStore.autoStep(
            handle = "kyrylo_m1", untilSeconds = 4_102_444_800L, optedOut = true,
            tried = true, nick = "kyrylo_m1", nowMs = 1_000L,
        )
        assertEquals(PeerDirectoryStore.AutoStep.Withdraw, step)
    }

    @Test
    fun `setting the nickname again after withdrawing clears the opt-out`() = runBlocking {
        val s = store(engine())
        s.autoPublish("kyrylo_m1")
        s.withdraw()
        s.publish("kyrylo_m1")

        assertFalse(s.optedOut)
        assertEquals("kyrylo_m1", s.handle.value)
    }

    @Test
    fun `a published nickname is renewed before it expires and not before`() {
        val now = 1_800_000_000_000L
        fun step(until: Long) = PeerDirectoryStore.autoStep("kyrylo_m1", until, false, true, null, now)
        assertEquals(PeerDirectoryStore.AutoStep.None, step(now / 1000 + 20 * 24 * 3600))
        assertEquals(
            PeerDirectoryStore.AutoStep.Publish("kyrylo_m1"),
            step(now / 1000 + 3 * 24 * 3600),
        )
        assertEquals(
            PeerDirectoryStore.AutoStep.Publish("kyrylo_m1"),
            step(now / 1000 - 10),
        )
    }

    @Test
    fun `a taken nickname is not retried on every start`() = runBlocking {
        val engine = MockEngine { respond("", HttpStatusCode.Conflict, json) }
        val s = store(engine)

        assertEquals(PeerDirectoryStore.Outcome.Taken, s.autoPublish("kyrylo_m1"))
        val before = engine.requestHistory.size
        assertNull(s.autoPublish("kyrylo_m1"))
        assertEquals(before, engine.requestHistory.size)
    }
}
