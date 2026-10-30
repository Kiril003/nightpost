package local.phantom.companion.data.erase

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.data.ClaimKey
import local.phantom.companion.data.ClaimSigner
import local.phantom.companion.data.DeviceClaim
import local.phantom.companion.data.DeviceIdentityStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EraseEverythingTest {

    private class FakeJournal(
        initial: EraseStep = EraseStep.NONE,
        initialDueAt: Long = 0L,
    ) : EraseJournal {
        private val map = mutableMapOf<String, Any>("step" to initial, "due_at" to initialDueAt)

        override fun read(): EraseStep = map["step"] as EraseStep

        override fun write(step: EraseStep) {
            map["step"] = step
            if (step != EraseStep.PENDING) map["due_at"] = 0L
        }

        override fun dueAtMs(): Long = map["due_at"] as Long

        override fun writePending(dueAtMs: Long) {
            map["step"] = EraseStep.PENDING
            map["due_at"] = dueAtMs
        }
    }

    private class FakeServer(
        var claimResult: EraseEverything.EraseServer.Result = EraseEverything.EraseServer.Result.Ok,
        var rootResult: EraseEverything.EraseServer.Result = EraseEverything.EraseServer.Result.Ok(10_000L),
        var cancelResult: EraseEverything.EraseServer.Result = EraseEverything.EraseServer.Result.Ok,
    ) : EraseEverything.EraseServer {
        var claimCalls = 0
        var rootCalls = 0
        var cancelCalls = 0

        override suspend fun claim(): EraseEverything.EraseServer.Result {
            claimCalls++
            return claimResult
        }

        override suspend fun root(): EraseEverything.EraseServer.Result {
            rootCalls++
            return rootResult
        }

        override suspend fun cancel(): EraseEverything.EraseServer.Result {
            cancelCalls++
            return cancelResult
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "platform-site").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root not found") as Nothing
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `killed after root resumes licence and wipe with zero server calls`() = runTest {
        val journal = FakeJournal(EraseStep.ROOT_DONE)
        val server = FakeServer()
        var licenceCalls = 0
        var wipeCalls = 0
        val erase = EraseEverything(
            journal = journal,
            server = server,
            licence = { licenceCalls++ },
            wipe = { wipeCalls++ },
        )

        val outcome = erase.resume()

        assertEquals(EraseEverything.Outcome.Done, outcome)
        assertEquals(1, licenceCalls)
        assertEquals(1, wipeCalls)
        assertEquals(0, server.claimCalls)
        assertEquals(0, server.rootCalls)
    }

    @Test
    fun `failed server step never wipes`() = runTest {
        val journal = FakeJournal()
        val server = FakeServer(rootResult = EraseEverything.EraseServer.Result.Unreachable)
        var wipeCalls = 0
        var wipeKeyCalls = 0
        val erase = EraseEverything(
            journal = journal,
            server = server,
            licence = {},
            wipe = { wipeCalls++ },
            wipeKey = { wipeKeyCalls++ },
        )

        val unreachable = erase.run(authorised = true)
        assertEquals(
            EraseEverything.Outcome.Failed(EraseStep.CLAIM_DONE, EraseEverything.EraseServer.Result.Unreachable),
            unreachable,
        )
        assertEquals(0, wipeCalls)
        assertEquals(0, wipeKeyCalls)
        assertEquals(EraseStep.CLAIM_DONE, journal.read())

        server.rootResult = EraseEverything.EraseServer.Result.Refused(403)
        val protectedOutcome = erase.run(authorised = true)
        assertEquals(EraseEverything.Outcome.Protected, protectedOutcome)
        assertEquals(0, wipeCalls)
        assertEquals(0, wipeKeyCalls)
        assertEquals(EraseStep.CLAIM_DONE, journal.read())
    }

    @Test
    fun `not authorised never calls anything`() = runTest {
        val journal = FakeJournal()
        val server = FakeServer()
        var wipeCalls = 0
        var wipeKeyCalls = 0
        val erase = EraseEverything(
            journal = journal,
            server = server,
            licence = {},
            wipe = { wipeCalls++ },
            wipeKey = { wipeKeyCalls++ },
        )

        val outcome = erase.run(authorised = false)

        assertEquals(EraseEverything.Outcome.NotAuthorised, outcome)
        assertEquals(0, server.claimCalls)
        assertEquals(0, server.rootCalls)
        assertEquals(0, wipeCalls)
        assertEquals(0, wipeKeyCalls)
        assertEquals(EraseStep.NONE, journal.read())
    }

    @Test
    fun `claim and root bodies share no value`() = runTest {
        val claimPair = Ed25519.generateKeypair()
        val rootPair = Ed25519.generateKeypair()
        val claimPub = Base64.getEncoder().withoutPadding().encodeToString(claimPair.publicKey)
        val rootPub = Base64.getEncoder().withoutPadding().encodeToString(rootPair.publicKey)
        val claims = ClaimSigner(
            key = { ClaimKey(claimPub, claimPair.privateKey) },
            nowMs = { 1_700_000_000_001L },
        )
        val rootSigner = EraseSigner(
            identity = {
                DeviceIdentityStore.Identity(
                    id = "root-id",
                    publicKeyB64 = rootPub,
                    privateKey = rootPair.privateKey,
                    dhPublicKeyB64 = "",
                    dhPrivateKey = ByteArray(32),
                )
            },
            nowMs = { 1_700_000_000_002L },
        )
        val bodies = mutableMapOf<String, String>()
        val client = EraseClient(
            claims = claims,
            rootSigner = rootSigner,
        ) { _, url, json ->
            bodies[url] = json.orEmpty()
            200 to """{"pending":true,"due_at":1700604800}"""
        }

        client.claim()
        client.root()

        val claimRaw = bodies.entries.single { it.key.endsWith("/api/erase/claim") }.value
        val rootRaw = bodies.entries.single { it.key.endsWith("/api/erase") }.value
        val claimJson = Json.parseToJsonElement(claimRaw).jsonObject
        val rootJson = Json.parseToJsonElement(rootRaw).jsonObject
        val expectedKeys = setOf("pub_key", "nonce", "issued_ms", "signature")
        assertEquals(expectedKeys, claimJson.keys)
        assertEquals(expectedKeys, rootJson.keys)

        for (k in expectedKeys) {
            val cv = claimJson[k]!!.jsonPrimitive.content
            val rv = rootJson[k]!!.jsonPrimitive.content
            assertFalse("claim value for $k appeared in root body", rootRaw.contains(cv))
            assertFalse("root value for $k appeared in claim body", claimRaw.contains(rv))
        }
        assertFalse(rootRaw.contains(claimPub))
        assertFalse(claimRaw.contains(rootPub))
    }

    @Test
    fun `sentences match the server vectors`() {
        val f = File(repoRoot(), "platform-site/server/tests/vectors/erase_v1.json")
        if (!f.isFile) fail("missing erase vectors: $f")
        val rootElement = Json.parseToJsonElement(f.readText())

        data class VectorCase(
            val seedHex: String,
            val pubKey: String,
            val nonce: String,
            val issuedMs: Long,
            val sentence: String,
            val signature: String,
        )

        val cases = mutableListOf<VectorCase>()
        fun collect(el: JsonElement, inheritedSeed: String?, inheritedPub: String?, inheritedNonce: String?, inheritedIssued: Long?) {
            when (el) {
                is JsonArray -> el.forEach { collect(it, inheritedSeed, inheritedPub, inheritedNonce, inheritedIssued) }
                is JsonObject -> {
                    val seed = (el["seed_hex"] ?: el["seed"] ?: el["private_key_hex"] ?: el["sk_hex"])
                        ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: inheritedSeed
                    val pub = (el["pub_key"] ?: el["public_key"] ?: el["pub"])
                        ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: inheritedPub
                    val nonce = (el["nonce"])
                        ?.let { (it as? JsonPrimitive)?.contentOrNull } ?: inheritedNonce
                    val issued = (el["issued_ms"])
                        ?.let { (it as? JsonPrimitive)?.longOrNull } ?: inheritedIssued
                    val sentence = (el["sentence"] ?: el["message"] ?: el["canonical"])
                        ?.let { (it as? JsonPrimitive)?.contentOrNull }
                    val signature = (el["signature"] ?: el["sig"] ?: el["signature_b64"])
                        ?.let { (it as? JsonPrimitive)?.contentOrNull }
                    if (sentence != null && signature != null && seed != null) {
                        val parts = sentence.split("|")
                        val resolvedPub = pub ?: parts.getOrNull(1).orEmpty()
                        val resolvedNonce = nonce ?: parts.getOrNull(2).orEmpty()
                        val resolvedIssued = issued ?: parts.getOrNull(3)?.toLongOrNull() ?: 0L
                        if (pub != null) assertEquals(pub, parts.getOrNull(1))
                        if (nonce != null) assertEquals(nonce, parts.getOrNull(2))
                        if (issued != null) assertEquals(issued, parts.getOrNull(3)?.toLongOrNull())
                        cases += VectorCase(seed, resolvedPub, resolvedNonce, resolvedIssued, sentence, signature)
                    }
                    el.values.forEach { child ->
                        if (child is JsonObject || child is JsonArray) {
                            collect(child, seed, pub, nonce, issued)
                        }
                    }
                }
                else -> Unit
            }
        }
        collect(rootElement, null, null, null, null)
        assertTrue("no vector cases found in $f", cases.isNotEmpty())

        var sawRoot = false
        var sawClaim = false
        for (c in cases) {
            val seedBytes = hex(c.seedHex)
            when {
                c.sentence.startsWith("NP-ERASE-v1|") -> {
                    sawRoot = true
                    assertEquals(c.sentence, EraseSigner.rootSentence(c.pubKey, c.nonce, c.issuedMs))
                    val signer = EraseSigner(
                        identity = {
                            DeviceIdentityStore.Identity("id", c.pubKey, seedBytes, "", ByteArray(32))
                        },
                        nowMs = { c.issuedMs },
                        nonceHex = { c.nonce },
                    )
                    val proof = signer.prove() ?: fail("missing root proof") as Nothing
                    assertEquals(c.pubKey, proof.pubKey)
                    assertEquals(c.nonce, proof.nonce)
                    assertEquals(c.issuedMs, proof.issuedMs)
                    assertEquals(c.signature, proof.signature)
                }
                c.sentence.startsWith("NP-ERASE-CLAIM-v1|") -> {
                    sawClaim = true
                    val built = DeviceClaim.Erase.sentence(c.pubKey, c.nonce, c.issuedMs)
                    assertEquals(c.sentence, built)
                    val sig = Base64.getEncoder().encodeToString(
                        Ed25519.sign(seedBytes, built.toByteArray(Charsets.UTF_8)),
                    )
                    assertEquals(c.signature, sig)
                }
                c.sentence.startsWith("NP-ERASE-CANCEL-v1|") -> {
                    assertEquals(c.sentence, EraseSigner.cancelSentence(c.pubKey, c.nonce, c.issuedMs))
                    val signer = EraseSigner(
                        identity = {
                            DeviceIdentityStore.Identity("id", c.pubKey, seedBytes, "", ByteArray(32))
                        },
                        nowMs = { c.issuedMs },
                        nonceHex = { c.nonce },
                    )
                    val proof = signer.cancel() ?: fail("missing cancel proof") as Nothing
                    assertEquals(c.signature, proof.signature)
                }
                else -> fail("unexpected vector domain: ${c.sentence}")
            }
        }
        assertTrue("root vector was not verified", sawRoot)
        assertTrue("claim vector was not verified", sawClaim)
    }

    @Test
    fun `resume after claim only does nothing`() = runTest {
        val journal = FakeJournal(EraseStep.CLAIM_DONE)
        val server = FakeServer()
        var wipeCalls = 0
        var wipeKeyCalls = 0
        val erase = EraseEverything(
            journal = journal,
            server = server,
            licence = {},
            wipe = { wipeCalls++ },
            wipeKey = { wipeKeyCalls++ },
        )

        val outcome = erase.resume()

        assertNull(outcome)
        assertEquals(0, server.claimCalls)
        assertEquals(0, server.rootCalls)
        assertEquals(0, wipeCalls)
        assertEquals(0, wipeKeyCalls)
    }

    @Test
    fun `cancel within grace restores the account`() = runTest {
        val journal = FakeJournal()
        val server = FakeServer(
            rootResult = EraseEverything.EraseServer.Result.Ok(50_000L),
            cancelResult = EraseEverything.EraseServer.Result.Ok,
        )
        var wipeKeyCalls = 0
        val erase = EraseEverything(
            journal = journal,
            server = server,
            licence = {},
            wipe = {},
            wipeKey = { wipeKeyCalls++ },
            nowMs = { 10_000L },
        )

        val runOutcome = erase.run(authorised = true)
        assertEquals(EraseEverything.Outcome.Pending(50_000L), runOutcome)
        assertEquals(EraseStep.PENDING, journal.read())
        assertEquals(50_000L, journal.dueAtMs())

        val cancelOutcome = erase.cancel(authorised = true)
        assertEquals(EraseEverything.Outcome.Restored, cancelOutcome)
        assertEquals(EraseStep.NONE, journal.read())
        assertEquals(0, wipeKeyCalls)
    }

    @Test
    fun `after due_at the key is gone`() = runTest {
        val now = 10 * EraseEverything.DAY_MS
        val journal = FakeJournal(initial = EraseStep.PENDING, initialDueAt = now - EraseEverything.DAY_MS - 1L)
        val server = FakeServer()
        var wipeKeyCalls = 0
        val erase = EraseEverything(
            journal = journal,
            server = server,
            licence = {},
            wipe = {},
            wipeKey = { wipeKeyCalls++ },
            nowMs = { now },
        )

        val outcome = erase.resume()

        assertEquals(EraseEverything.Outcome.Done, outcome)
        assertEquals(1, wipeKeyCalls)
        assertEquals(EraseStep.NONE, journal.read())
        erase.resume()
        assertEquals("новий ключ після видалення не стирається знову", 1, wipeKeyCalls)
        assertEquals(0, server.claimCalls)
        assertEquals(0, server.rootCalls)
        assertEquals(0, server.cancelCalls)
    }

    @Test
    fun `pending with unknown due_at keeps the key`() = runTest {
        for ((due, now) in listOf(0L to 10 * EraseEverything.DAY_MS, 5 * EraseEverything.DAY_MS to 5 * EraseEverything.DAY_MS + 1L)) {
            val journal = FakeJournal(initial = EraseStep.PENDING, initialDueAt = due)
            var wipeKeyCalls = 0
            val erase = EraseEverything(
                journal = journal,
                server = FakeServer(),
                licence = {},
                wipe = {},
                wipeKey = { wipeKeyCalls++ },
                nowMs = { now },
            )
            assertEquals(EraseEverything.Outcome.Pending(due), erase.resume())
            assertEquals("due=$due now=$now", 0, wipeKeyCalls)
            assertEquals(EraseStep.PENDING, journal.read())
        }
    }

    @Test
    fun `cancel answered 404 wipes the key`() = runTest {
        val journal = FakeJournal(initial = EraseStep.PENDING, initialDueAt = 50_000L)
        val server = FakeServer(cancelResult = EraseEverything.EraseServer.Result.Refused(404))
        var wipeKeyCalls = 0
        val erase = EraseEverything(
            journal = journal,
            server = server,
            licence = {},
            wipe = {},
            wipeKey = { wipeKeyCalls++ },
            nowMs = { 10_000L },
        )

        val outcome = erase.cancel(authorised = true)

        assertEquals(EraseEverything.Outcome.Done, outcome)
        assertEquals(1, wipeKeyCalls)
        assertEquals(EraseStep.NONE, journal.read())
    }

    @Test
    fun `server due_at is seconds, the journal keeps milliseconds`() {
        assertEquals(1_700_604_800_000L, EraseClient.parseDueAt("""{"pending":true,"due_at":1700604800}"""))
        assertEquals(1_700_604_800_123L, EraseClient.parseDueAt("""{"due_at_ms":1700604800123}"""))
        assertEquals(0L, EraseClient.parseDueAt("""{"pending":true}"""))
    }

    @Test
    fun `pending deletion keeps phone data and wallet until the due date`() = runTest {
        val due = 20 * EraseEverything.DAY_MS
        var now = 13 * EraseEverything.DAY_MS
        val journal = FakeJournal()
        val server = FakeServer(rootResult = EraseEverything.EraseServer.Result.Ok(due))
        var wipeCalls = 0
        var wipeKeyCalls = 0
        var licenceCalls = 0
        val erase = EraseEverything(
            journal = journal,
            server = server,
            licence = { licenceCalls++ },
            wipe = { wipeCalls++ },
            wipeKey = { wipeKeyCalls++ },
            nowMs = { now },
        )

        assertEquals(EraseEverything.Outcome.Pending(due), erase.run(authorised = true))
        assertEquals(0, wipeCalls)
        assertEquals(0, wipeKeyCalls)
        assertEquals(0, licenceCalls)

        now = due
        assertEquals(EraseEverything.Outcome.Pending(due), erase.resume())
        assertEquals(0, wipeCalls + wipeKeyCalls + licenceCalls)

        now = due + EraseEverything.DAY_MS
        assertEquals(EraseEverything.Outcome.Done, erase.resume())
        assertEquals(1, wipeKeyCalls)
        assertEquals(1, licenceCalls)
        assertEquals(EraseStep.NONE, journal.read())
    }

    @Test
    fun `server without a due date still gets the seven day grace`() = runTest {
        val now = 3 * EraseEverything.DAY_MS
        val journal = FakeJournal()
        val erase = EraseEverything(
            journal = journal,
            server = FakeServer(rootResult = EraseEverything.EraseServer.Result.Ok),
            licence = {},
            wipe = {},
            nowMs = { now },
        )

        assertEquals(EraseEverything.Outcome.Pending(now + 7 * EraseEverything.DAY_MS), erase.run(authorised = true))
        assertEquals(now + 7 * EraseEverything.DAY_MS, journal.dueAtMs())
    }

    @Test
    fun `directory wipe keeps only the listed paths`() {
        val tmp = Files.createTempDirectory("erase-test").toFile()
        try {
            val kept = File(tmp, "shared_prefs/keep.xml").apply {
                parentFile.mkdirs()
                writeText("keep")
            }
            val otherPref = File(tmp, "shared_prefs/other.xml").apply { writeText("other") }
            val dbFile = File(tmp, "databases/phantom.db").apply {
                parentFile.mkdirs()
                writeText("db")
            }
            wipeDirectoryExcept(tmp, setOf("shared_prefs/keep.xml"))
            assertTrue(kept.exists())
            assertFalse(otherPref.exists())
            assertFalse(dbFile.exists())
        } finally {
            tmp.deleteRecursively()
        }
    }
}
