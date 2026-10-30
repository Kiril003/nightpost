package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun sseHeaders() = headersOf(HttpHeaders.ContentType to listOf("text/event-stream"))
private fun jsonHeaders() = headersOf(HttpHeaders.ContentType to listOf("application/json"))

private fun sseOk(text: String = "відповідь"): String =
    """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]}}],"usageMetadata":{"totalTokenCount":2}}""" + "\n\n"

private class JcaTestSigner(
    private val fixedNonce: String = "0123456789abcdef0123456789abcdef",
    private val fixedMs: Long = 1_760_000_000_000L,
) : BrainRequestSigner {
    val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val rawPub: ByteArray = keyPair.public.encoded.takeLast(32).toByteArray()
    override val publicKeyB64: String = Base64.getEncoder().withoutPadding().encodeToString(rawPub)

    var lastSignedMessage: ByteArray? = null

    override fun sign(message: ByteArray): String {
        lastSignedMessage = message
        val sig = Signature.getInstance("Ed25519").apply {
            initSign(keyPair.private)
            update(message)
        }.sign()
        return Base64.getEncoder().withoutPadding().encodeToString(sig)
    }

    override fun newNonce(): String = fixedNonce
    override fun nowMs(): Long = fixedMs

    fun verify(message: ByteArray, signatureB64: String): Boolean {
        val sigBytes = Base64.getDecoder().decode(signatureB64)
        return Signature.getInstance("Ed25519").apply {
            initVerify(keyPair.public)
            update(message)
        }.verify(sigBytes)
    }
}

class NightpostBrainAttestTest {

    @Test
    fun `brain request sends the 4 signed headers and legacy install header matching server message format`() = runTest {
        val signer = JcaTestSigner(
            fixedNonce = "abcdef0123456789abcdef0123456789",
            fixedMs = 1_760_123_456_789L,
        )
        var attestedCount = 0
        var generateHeaders: Map<String, String> = emptyMap()
        var generateBodyBytes: ByteArray = ByteArray(0)
        var generateModel = ""

        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/brain/status" -> respond(
                        """{"brain":true,"models":["gemini-3.8-flash"],"daily":40}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                    "/api/brain/attest" -> {
                        attestedCount++
                        val bodyText = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        val obj = testJson.parseToJsonElement(bodyText).jsonObject
                        assertEquals(signer.publicKeyB64, obj["pub_key"]!!.jsonPrimitive.content)
                        assertEquals(signer.newNonce(), obj["nonce"]!!.jsonPrimitive.content)
                        assertEquals(signer.nowMs().toString(), obj["issued_ms"]!!.jsonPrimitive.content)
                        assertNull("integrity_token must be omitted when provider returns null", obj["integrity_token"])
                        val expectedAttestMsg =
                            "NP-ATTEST-v1|${signer.publicKeyB64}|${signer.newNonce()}|${signer.nowMs()}".toByteArray()
                        assertTrue(signer.verify(expectedAttestMsg, obj["signature"]!!.jsonPrimitive.content))
                        respond(
                            """{"device_id":"0123456789abcdef0123456789abcdef","tier":"free","daily":10,"used":0}""",
                            HttpStatusCode.OK,
                            jsonHeaders(),
                        )
                    }
                    else -> {
                        generateModel = request.url.encodedPath.substringAfter("models/").substringBefore(":")
                        generateBodyBytes = (request.body as OutgoingContent.ByteArrayContent).bytes()
                        generateHeaders = buildMap {
                            request.headers.names().forEach { name ->
                                put(name, request.headers[name].orEmpty())
                            }
                        }
                        respond(sseOk("привіт"), HttpStatusCode.OK, sseHeaders())
                    }
                }
            },
        ) {
            install(ContentNegotiation) { json(testJson) }
            expectSuccess = false
        }

        val route = NightpostBrainRoute(
            base = "https://nightpost.test",
            installId = { "install-id-legacy-012345" },
            allowed = { true },
            httpClient = client,
            signer = signer,
        )

        val chunks = route.complete(AIIntent.Text(prompt = "тестовий запит")).toList()
        assertEquals("привіт", chunks.filterIsInstance<AIChunk.Final>().single().text)
        assertEquals("attest must run once on first call", 1, attestedCount)

        assertEquals("install-id-legacy-012345", generateHeaders["X-Nightpost-Device"])
        assertEquals(signer.publicKeyB64, generateHeaders["X-Nightpost-Key"])
        assertEquals("1760123456789", generateHeaders["X-Nightpost-Ts"])
        assertEquals("abcdef0123456789abcdef0123456789", generateHeaders["X-Nightpost-Nonce"])

        val bodySha256 = MessageDigest.getInstance("SHA-256")
            .digest(generateBodyBytes)
            .joinToString("") { "%02x".format(it) }
        val expectedBrainMsg =
            "NP-BRAIN-v1|${signer.publicKeyB64}|abcdef0123456789abcdef0123456789|1760123456789|$generateModel|$bodySha256"
                .toByteArray(Charsets.UTF_8)
        assertTrue(
            "X-Nightpost-Sig must verify over exact server message",
            signer.verify(expectedBrainMsg, generateHeaders["X-Nightpost-Sig"].orEmpty()),
        )

        // Second call reuses the attested state without calling /api/brain/attest again.
        route.complete(AIIntent.Text(prompt = "другий запит")).toList()
        assertEquals(1, attestedCount)
    }

    @Test
    fun `403 attest_first triggers one attest and one retry without looping`() = runTest {
        val signer = JcaTestSigner()
        var attestCalls = 0
        var generateCalls = 0

        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/brain/status" -> respond(
                        """{"brain":true,"models":["gemini-3.8-flash"],"daily":40}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                    "/api/brain/attest" -> {
                        attestCalls++
                        respond(
                            """{"device_id":"0123456789abcdef0123456789abcdef","tier":"free","daily":10,"used":0}""",
                            HttpStatusCode.OK,
                            jsonHeaders(),
                        )
                    }
                    else -> {
                        generateCalls++
                        if (generateCalls == 1) {
                            respond(
                                """{"error":"attest_first"}""",
                                HttpStatusCode.Forbidden,
                                jsonHeaders(),
                            )
                        } else {
                            respond(sseOk("після повторної атестації"), HttpStatusCode.OK, sseHeaders())
                        }
                    }
                }
            },
        ) {
            install(ContentNegotiation) { json(testJson) }
            expectSuccess = false
        }

        var persistedAttested = true
        val route = NightpostBrainRoute(
            base = "https://nightpost.test",
            installId = { "install-id-legacy-012345" },
            allowed = { true },
            httpClient = client,
            signer = signer,
            attestedStore = BrainAttestedStore.ofBoolean(
                read = { persistedAttested },
                write = { persistedAttested = it },
            ),
        )

        val chunks = route.complete(AIIntent.Text(prompt = "питай")).toList()
        assertEquals("після повторної атестації", chunks.filterIsInstance<AIChunk.Final>().single().text)
        assertEquals(1, attestCalls)
        assertEquals(2, generateCalls)
    }

    @Test
    fun `persistent 403 attest_first stops after one retry and does not loop`() = runTest {
        val signer = JcaTestSigner()
        var attestCalls = 0
        var generateCalls = 0

        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/brain/status" -> respond(
                        """{"brain":true,"models":["gemini-3.8-flash"],"daily":40}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                    "/api/brain/attest" -> {
                        attestCalls++
                        respond(
                            """{"device_id":"0123456789abcdef0123456789abcdef","tier":"free","daily":10,"used":0}""",
                            HttpStatusCode.OK,
                            jsonHeaders(),
                        )
                    }
                    else -> {
                        generateCalls++
                        respond(
                            """{"error":"attest_first"}""",
                            HttpStatusCode.Forbidden,
                            jsonHeaders(),
                        )
                    }
                }
            },
        ) {
            install(ContentNegotiation) { json(testJson) }
            expectSuccess = false
        }

        val route = NightpostBrainRoute(
            base = "https://nightpost.test",
            installId = { "install-id-legacy-012345" },
            allowed = { true },
            httpClient = client,
            signer = signer,
            attestedStore = BrainAttestedStore.ofBoolean(read = { true }, write = {}),
        )

        val chunks = route.complete(AIIntent.Text(prompt = "питай")).toList()
        val error = chunks.filterIsInstance<AIChunk.ErrorChunk>().single()
        val rejected = error.cause as local.phantom.companion.core.ai.AIError.CloudRejected
        // Аудит №4: 403 від нашого сервера — не «перевір свій ключ» людині без ключа.
        assertEquals(502, rejected.httpCode)
        assertEquals(1, attestCalls)
        assertEquals(2, generateCalls)
    }

    @Test
    fun `signed status request sends NP-BRAIN-v1 headers with model status and empty body hash`() = runTest {
        val signer = JcaTestSigner(
            fixedNonce = "fedcba9876543210fedcba9876543210",
            fixedMs = 1_760_555_000_000L,
        )
        var statusHeaders: Map<String, String> = emptyMap()

        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/brain/status" -> {
                        statusHeaders = buildMap {
                            request.headers.names().forEach { name ->
                                put(name, request.headers[name].orEmpty())
                            }
                        }
                        respond(
                            """{"brain":true,"models":["gemini-3.8-flash"],"tier":"personal","daily":250,"used":3}""",
                            HttpStatusCode.OK,
                            jsonHeaders(),
                        )
                    }
                    else -> error("unexpected path ${request.url.encodedPath}")
                }
            },
        ) {
            install(ContentNegotiation) { json(testJson) }
            expectSuccess = false
        }

        val route = NightpostBrainRoute(
            base = "https://nightpost.test",
            installId = { "install-id-legacy-012345" },
            allowed = { true },
            httpClient = client,
            signer = signer,
        )

        assertEquals(NightpostBrainRoute.Standing.UP, route.standing())
        assertEquals(signer.publicKeyB64, statusHeaders["X-Nightpost-Key"])
        assertEquals("1760555000000", statusHeaders["X-Nightpost-Ts"])
        assertEquals("fedcba9876543210fedcba9876543210", statusHeaders["X-Nightpost-Nonce"])
        val emptySha256 = MessageDigest.getInstance("SHA-256")
            .digest(ByteArray(0))
            .joinToString("") { "%02x".format(it) }
        val expectedMsg =
            "NP-BRAIN-v1|${signer.publicKeyB64}|fedcba9876543210fedcba9876543210|1760555000000|status|$emptySha256"
                .toByteArray(Charsets.UTF_8)
        assertTrue(
            "status signature must verify over model=status and empty body sha256",
            signer.verify(expectedMsg, statusHeaders["X-Nightpost-Sig"].orEmpty()),
        )
    }

    @Test
    fun `legacy boolean attested_v1 migrates to key_only and upgrades to integrity when provider delivers token`() = runTest {
        val signer = JcaTestSigner()
        var storedKind: String? = null
        var legacyBool = true
        var currentIntegrityToken: String? = null
        val seenIntegrityTokens = mutableListOf<String?>()

        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/brain/status" -> respond(
                        """{"brain":true,"models":["gemini-3.8-flash"],"daily":40}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                    "/api/brain/attest" -> {
                        val bodyText = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        val obj = testJson.parseToJsonElement(bodyText).jsonObject
                        seenIntegrityTokens += obj["integrity_token"]?.jsonPrimitive?.content
                        respond(
                            """{"device_id":"0123456789abcdef0123456789abcdef","tier":"free","daily":40,"used":0}""",
                            HttpStatusCode.OK,
                            jsonHeaders(),
                        )
                    }
                    else -> respond(sseOk("відповідь"), HttpStatusCode.OK, sseHeaders())
                }
            },
        ) {
            install(ContentNegotiation) { json(testJson) }
            expectSuccess = false
        }

        val store = BrainAttestedStore.migrate(
            readString = { storedKind },
            readLegacyBoolean = { legacyBool },
            writeString = { storedKind = it },
            clearLegacyBoolean = { legacyBool = false },
        )

        val route = NightpostBrainRoute(
            base = "https://nightpost.test",
            installId = { "install-id-legacy-012345" },
            allowed = { true },
            httpClient = client,
            signer = signer,
            integrityTokenProvider = IntegrityTokenProvider { currentIntegrityToken },
            attestedStore = store,
        )

        assertEquals("key_only", storedKind)
        assertEquals(false, legacyBool)

        // Provider returns null -> key_only is sufficient, so no /api/brain/attest call is made.
        route.complete(AIIntent.Text(prompt = "раз")).toList()
        assertTrue(seenIntegrityTokens.isEmpty())

        // Provider now delivers a Play Integrity token -> upgrades from key_only to integrity.
        currentIntegrityToken = "play-integrity-compact-jwe"
        route.complete(AIIntent.Text(prompt = "два")).toList()
        assertEquals(listOf("play-integrity-compact-jwe"), seenIntegrityTokens)
        assertEquals("integrity", storedKind)

        // Subsequent calls with integrity token short-circuit because stored kind is already integrity.
        route.complete(AIIntent.Text(prompt = "три")).toList()
        assertEquals(1, seenIntegrityTokens.size)
    }

    @Test
    fun `failed initial attest does not force a second attest and never says check the key`() = runTest {
        val signer = JcaTestSigner()
        var attestCalls = 0
        var generateCalls = 0
        var generateStatus = HttpStatusCode.Forbidden
        var generateBody = """{"error":"attest_first"}"""

        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/brain/status" -> respond(
                        """{"brain":true,"models":["gemini-3.8-flash"],"daily":10}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                    "/api/brain/attest" -> {
                        attestCalls++
                        respond("""{"error":"bad_signature"}""", HttpStatusCode.Unauthorized, jsonHeaders())
                    }
                    else -> {
                        generateCalls++
                        respond(generateBody, generateStatus, jsonHeaders())
                    }
                }
            },
        ) {
            install(ContentNegotiation) { json(testJson) }
            expectSuccess = false
        }

        val route = NightpostBrainRoute(
            base = "https://nightpost.test",
            installId = { "install-id-legacy-012345" },
            allowed = { true },
            httpClient = client,
            signer = signer,
        )

        val chunks403 = route.complete(AIIntent.Text(prompt = "питай")).toList()
        assertEquals("failed initial attest must not trigger a second forced attest", 1, attestCalls)
        assertEquals("generate must not retry when initial attest failed", 1, generateCalls)
        val err403 = chunks403.filterIsInstance<AIChunk.ErrorChunk>().single().cause as local.phantom.companion.core.ai.AIError.CloudRejected
        assertEquals(502, err403.httpCode)

        generateStatus = HttpStatusCode.Unauthorized
        generateBody = """{"error":"bad_signature"}"""
        val chunks401 = route.complete(AIIntent.Text(prompt = "вдруге")).toList()
        val err401 = chunks401.filterIsInstance<AIChunk.ErrorChunk>().single().cause as local.phantom.companion.core.ai.AIError.CloudRejected
        assertEquals(502, err401.httpCode)
    }

    @Test
    fun `attest limit on a shared address does not end in check the key`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/brain/status" -> respond("""{"brain":true,"daily":10}""", HttpStatusCode.OK, jsonHeaders())
                    "/api/brain/attest" -> respond("""{"error":"attest_limit"}""", HttpStatusCode.TooManyRequests, jsonHeaders())
                    else -> respond("""{"error":"attest_first"}""", HttpStatusCode.Forbidden, jsonHeaders())
                }
            },
        ) {
            install(ContentNegotiation) { json(testJson) }
            expectSuccess = false
        }
        val route = NightpostBrainRoute(
            base = "https://nightpost.test",
            installId = { "install-id-legacy-012345" },
            allowed = { true },
            httpClient = client,
            signer = JcaTestSigner(),
        )

        val cause = route.complete(AIIntent.Text(prompt = "питай")).toList()
            .filterIsInstance<AIChunk.ErrorChunk>().single().cause
        val code = (cause as? local.phantom.companion.core.ai.AIError.CloudRejected)?.httpCode
        assertTrue("$code", code != 401 && code != 403)
    }

    /** Аудит №5: годинник збився чи портал перехопив — це «не знаємо», а не «розуму немає» на 10 хвилин. */
    @Test
    fun `a 401 on the signed status is unknown, not no brain`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/brain/status" -> respond("""{"error":"bad_signature"}""", HttpStatusCode.Unauthorized, jsonHeaders())
                    else -> respond(sseOk(), HttpStatusCode.OK, sseHeaders())
                }
            },
        ) {
            install(ContentNegotiation) { json(testJson) }
            expectSuccess = false
        }
        val route = NightpostBrainRoute(
            base = "https://nightpost.test",
            installId = { "install-id-legacy-012345" },
            allowed = { true },
            httpClient = client,
            signer = JcaTestSigner(),
        )

        assertEquals(NightpostBrainRoute.Standing.UNKNOWN, route.standing())
        assertEquals(setOf(local.phantom.companion.core.ai.Route.NIGHTPOST_SERVER), route.availableRoutes())
    }
}
