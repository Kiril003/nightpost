package local.phantom.companion.data.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.Bip39
import local.phantom.companion.data.ClaimKey
import local.phantom.companion.data.CustomCircle
import local.phantom.companion.data.DeviceIdentityStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupFormatTest {

    private val pass12 = "correct-horse-battery".toCharArray()

    private fun companionRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "feature-stream").isDirectory }
            ?: error("phantom-companion root not found")

    private val bip39Words: List<String> by lazy {
        File(companionRoot(), "core-solana/src/main/assets/bip39_english.txt")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    @Test
    fun `1 header holds Argon2id m=65536 and t=3`() {
        val plain = "nightpost-header-test-payload".toByteArray(Charsets.UTF_8)
        val sealed = ByteArrayOutputStream()

        BackupFormat.seal(
            input = ByteArrayInputStream(plain),
            output = sealed,
            passphrase = pass12.copyOf(),
        )

        val bytes = sealed.toByteArray()
        val header = BackupFormat.readHeader(ByteArrayInputStream(bytes))
        assertNotNull(header)
        header!!
        assertEquals(BackupFormat.FORMAT_VERSION, header.v)
        assertEquals("argon2id", header.kdf)
        assertEquals(65_536, header.m)
        assertEquals(3, header.t)
        assertEquals(1, header.p)
        assertEquals(BackupFormat.CHUNK_SIZE, header.chunkSize)

        val opened = ByteArrayOutputStream()
        assertTrue(
            BackupFormat.open(
                input = ByteArrayInputStream(bytes),
                output = opened,
                passphrase = pass12.copyOf(),
            ),
        )
        assertArrayEquals(plain, opened.toByteArray())
    }

    @Test
    fun `2 a 100 MB stream round-trips with bounded memory`() {
        val totalBytes = 100L * 1024L * 1024L
        val tempFile = Files.createTempFile("nightpost-100m-", ".backup").toFile()
        try {
            val keyStore = BackupKeyStore.inMemory()
            val sealedKey = BackupFormat.mintKey(
                passphrase = pass12.copyOf(),
                kdf = BackupFormat.KdfParams.Default,
            )
            keyStore.save(sealedKey)

            System.gc()
            val runtime = Runtime.getRuntime()
            val baselineHeap = runtime.totalMemory() - runtime.freeMemory()
            var peakDelta = 0L

            fun sampleHeap() {
                val used = runtime.totalMemory() - runtime.freeMemory()
                val delta = used - baselineHeap
                if (delta > peakDelta) peakDelta = delta
            }

            BufferedOutputStream(FileOutputStream(tempFile), BackupFormat.CHUNK_SIZE).use { fileOut ->
                BackupFormat.reseal(
                    input = DeterministicStream(totalBytes, onChunk = ::sampleHeap),
                    output = fileOut,
                    sealedKey = sealedKey,
                )
            }

            assertTrue("sealed file should exceed 100 MB", tempFile.length() > totalBytes)

            val verifier = VerifyingOutputStream(totalBytes, onChunk = ::sampleHeap)
            val ok = BufferedInputStream(FileInputStream(tempFile), BackupFormat.CHUNK_SIZE).use { fileIn ->
                BackupFormat.openWithKey(
                    input = fileIn,
                    output = verifier,
                    key = sealedKey.rawKey(),
                )
            }

            assertTrue("100 MB stream must authenticate and open", ok)
            assertEquals(totalBytes, verifier.count)

            System.gc()
            val settledDelta = (runtime.totalMemory() - runtime.freeMemory()) - baselineHeap
            val ceilingBytes = 32L * 1024L * 1024L
            assertTrue(
                "settled heap delta (${settledDelta / (1024 * 1024)} MiB) must stay under 32 MiB",
                settledDelta < ceilingBytes,
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `3 an 11-character passphrase is refused`() {
        val plain = "secret-backup-content".toByteArray(Charsets.UTF_8)
        for (shortPass in listOf("", "a", "1234567890", "12345678901", "            ")) {
            val out = ByteArrayOutputStream()
            try {
                BackupFormat.seal(
                    input = ByteArrayInputStream(plain),
                    output = out,
                    passphrase = shortPass.toCharArray(),
                )
                fail("Expected passphrase of length ${shortPass.length} ('$shortPass') to be refused")
            } catch (_: IllegalArgumentException) {
                // expected
            }
            assertEquals(0, out.size())
        }

        // Exactly 12 characters is accepted and round-trips
        val twelve = "123456789012".toCharArray()
        val sealed = ByteArrayOutputStream()
        BackupFormat.seal(
            input = ByteArrayInputStream(plain),
            output = sealed,
            passphrase = twelve.copyOf(),
        )
        val opened = ByteArrayOutputStream()
        assertTrue(
            BackupFormat.open(
                input = ByteArrayInputStream(sealed.toByteArray()),
                output = opened,
                passphrase = twelve.copyOf(),
            ),
        )
        assertArrayEquals(plain, opened.toByteArray())

        // Opening with an 11-character passphrase fails closed without running KDF
        val badOut = ByteArrayOutputStream()
        assertFalse(
            BackupFormat.open(
                input = ByteArrayInputStream(sealed.toByteArray()),
                output = badOut,
                passphrase = "12345678901".toCharArray(),
            ),
        )
        assertEquals(0, badOut.size())
    }

    @Test
    fun `4 a wrong passphrase fails closed`() {
        val plain = ByteArray(BackupFormat.CHUNK_SIZE * 2 + 1234) { (it * 13 + 5).toByte() }
        val keyStore = BackupKeyStore.inMemory()
        val sealed = ByteArrayOutputStream()

        BackupFormat.seal(
            input = ByteArrayInputStream(plain),
            output = sealed,
            passphrase = pass12.copyOf(),
            keyStore = keyStore,
        )
        val sealedBytes = sealed.toByteArray()

        // Wrong passphrase fails closed with 0 bytes written
        val wrongOut = ByteArrayOutputStream()
        assertFalse(
            BackupFormat.open(
                input = ByteArrayInputStream(sealedBytes),
                output = wrongOut,
                passphrase = "wrong-horse-battery-99".toCharArray(),
            ),
        )
        assertEquals(0, wrongOut.size())

        // Tampered ciphertext byte fails closed
        val tampered = sealedBytes.copyOf()
        tampered[tampered.size - 10] = (tampered[tampered.size - 10].toInt() xor 0xFF).toByte()
        val tamperedOut = ByteArrayOutputStream()
        assertFalse(
            BackupFormat.open(
                input = ByteArrayInputStream(tampered),
                output = tamperedOut,
                passphrase = pass12.copyOf(),
            ),
        )

        // Truncated stream (missing last chunk) fails closed
        val truncated = sealedBytes.copyOf(sealedBytes.size - 500)
        val truncatedOut = ByteArrayOutputStream()
        assertFalse(
            BackupFormat.open(
                input = ByteArrayInputStream(truncated),
                output = truncatedOut,
                passphrase = pass12.copyOf(),
            ),
        )

        // Automatic reseal without passphrase using BackupKeyStore still opens with original passphrase
        // and fails closed with wrong passphrase
        val resealed = ByteArrayOutputStream()
        assertNotNull(
            BackupFormat.reseal(
                input = ByteArrayInputStream(plain),
                output = resealed,
                keyStore = keyStore,
            ),
        )
        val resealedWrongOut = ByteArrayOutputStream()
        assertFalse(
            BackupFormat.open(
                input = ByteArrayInputStream(resealed.toByteArray()),
                output = resealedWrongOut,
                passphrase = "another-wrong-passphrase".toCharArray(),
            ),
        )
        assertEquals(0, resealedWrongOut.size())

        val resealedRightOut = ByteArrayOutputStream()
        assertTrue(
            BackupFormat.open(
                input = ByteArrayInputStream(resealed.toByteArray()),
                output = resealedRightOut,
                passphrase = pass12.copyOf(),
            ),
        )
        assertArrayEquals(plain, resealedRightOut.toByteArray())
    }

    @Test
    fun `5 a manifest scan finds no wallet word list entries`() {
        val bip39 = Bip39(bip39Words)
        val vaultWords = bip39.fromEntropy(ByteArray(32) { (it * 31 + 7).toByte() })
        assertEquals(24, vaultWords.size)

        // Sanity-check that scanWalletWords catches wallet words when present in raw text
        val rawVaultDump = """{"phantom_solana_vault":{"secret":"${vaultWords.joinToString(" ")}"}}"""
        val detectedInRaw = BackupManifest.scanWalletWords(rawVaultDump, bip39Words)
        assertTrue("scanner must detect BIP-39 words in raw dump", detectedInRaw.containsAll(vaultWords))

        val rootEd = Ed25519.generateKeypair()
        val rootDh = X25519.generateKeypair()
        val claimEd = Ed25519.generateKeypair()
        val b64 = Base64.getEncoder().withoutPadding()

        val identity = DeviceIdentityStore.Identity(
            id = "a1b2c3d4e5f60718",
            publicKeyB64 = b64.encodeToString(rootEd.publicKey),
            privateKey = rootEd.privateKey,
            dhPublicKeyB64 = b64.encodeToString(rootDh.publicKey),
            dhPrivateKey = rootDh.privateKey,
        )
        val claimKey = ClaimKey(
            publicKeyB64 = b64.encodeToString(claimEd.publicKey),
            privateKey = claimEd.privateKey,
        )

        val personPeer = PeerIdentity(
            id = "1122334455667788",
            name = "Олена",
            kind = PeerIdentity.Kind.PERSON,
            pubKey = b64.encodeToString(Ed25519.generateKeypair().publicKey),
            dhPubKey = b64.encodeToString(X25519.generateKeypair().publicKey),
            addedAtMs = 1_760_000_000_000L,
            verifiedAtMs = 1_760_000_100_000L,
            keySource = PeerIdentity.KeySource.CAMERA,
            avatarThumbB64 = "cGhvdG8tYnl0ZXM", // media thumb must be stripped
        )
        val pairedPcPeer = PeerIdentity(
            id = "9988776655443322",
            name = "Мій ноутбук",
            kind = PeerIdentity.Kind.DEVICE, // pairing device must be excluded
            pubKey = b64.encodeToString(Ed25519.generateKeypair().publicKey),
            dhPubKey = b64.encodeToString(X25519.generateKeypair().publicKey),
        )

        val textMessage = PeerMessage(
            id = "m-1",
            fromId = identity.id,
            toId = personPeer.id,
            body = "Зустрінемось біля кав'ярні о 18:00",
            sentAtMs = 1_760_000_200_000L,
            state = PeerMessage.State.DELIVERED,
        )
        val mediaChunkMessage = PeerMessage(
            id = "m-chunk",
            fromId = identity.id,
            toId = personPeer.id,
            body = PeerFile.encodeChunk("ab".repeat(16), 0, 1, byteArrayOf(1, 2, 3)),
            sentAtMs = 1_760_000_201_000L,
            state = PeerMessage.State.DELIVERED,
        )
        val savedNote = PeerMessage(
            id = "s-1",
            fromId = identity.id,
            toId = "chat_saved_notes",
            body = "Купити каву",
            sentAtMs = 1_760_000_300_000L,
            state = PeerMessage.State.DELIVERED,
        )

        val memoryItem = buildJsonObject {
            put("id", JsonPrimitive("mem-001"))
            put("kind", JsonPrimitive("fact"))
            put("text", JsonPrimitive("Любить фільтр-каву"))
        }

        // Candidate preferences include valid settings PLUS forbidden wallet words, pairing credentials, and media paths
        val candidateSettings = mapOf(
            "phantom_quiet_hours_v1" to mapOf("on" to true, "from" to "22:00", "except" to setOf("aa11")),
            "phantom_theme_prefs" to mapOf("ui_theme_mode" to "Dark", "app_night_mode_set" to 2),
            "phantom_solana_vault" to mapOf(
                "secret" to vaultWords.joinToString(" "),
                "secret_bound" to vaultWords.joinToString(" "),
                "main" to "So11111111111111111111111111111111111111112",
            ),
            "phantom_solana" to mapOf(
                "mnemonic" to vaultWords.take(12).joinToString(" "),
                "cash" to vaultWords.joinToString(" "),
            ),
            "phantom_pair_v1" to mapOf(
                "paired_device" to """{"deviceJwt":"jwt-secret-token","deviceEd25519Priv":"priv-key"}""",
            ),
            "phantom_own_self_v1" to mapOf("peers" to "[\"9988776655443322\"]"),
            "msgr/media" to mapOf("blob" to "photo.jpg"),
            "peer_photos" to mapOf("avatar" to "avatar.webp"),
        )

        val manifest = BackupManifest.collect(
            identity = identity,
            claimKey = claimKey,
            peers = listOf(personPeer, pairedPcPeer),
            circles = mapOf(personPeer.id to setOf("close"), pairedPcPeer.id to setOf("personal")),
            customCircles = listOf(CustomCircle("u:12345678", "Свої")),
            declined = setOf("deadbeefdeadbeef"),
            blocked = setOf("0011223344556677"),
            handles = mapOf(personPeer.id to "olena_kyiv"),
            messages = listOf(textMessage, mediaChunkMessage),
            savedNotes = listOf(savedNote, mediaChunkMessage),
            readReceiptsEnabled = true,
            groupReads = mapOf("grp-1" to 1_760_000_400_000L),
            memoryItems = listOf(memoryItem),
            candidateSettings = candidateSettings,
            atMs = 1_760_000_500_000L,
        )

        val keyStore = BackupKeyStore.inMemory()

        // 1) Manual seal (with passphrase)
        val manualOut = ByteArrayOutputStream()
        BackupFormat.sealManifest(
            manifest = manifest,
            output = manualOut,
            passphrase = pass12.copyOf(),
            keyStore = keyStore,
        )

        // 2) Automatic reseal (without passphrase, using stored K)
        val autoOut = ByteArrayOutputStream()
        assertNotNull(
            BackupFormat.resealManifest(
                manifest = manifest,
                output = autoOut,
                keyStore = keyStore,
            ),
        )

        for ((label, bytes) in listOf("manual" to manualOut.toByteArray(), "automatic" to autoOut.toByteArray())) {
            val plainOut = ByteArrayOutputStream()
            assertTrue(
                "$label backup must open with passphrase",
                BackupFormat.open(ByteArrayInputStream(bytes), plainOut, pass12.copyOf()),
            )
            val plaintextManifest = plainOut.toByteArray().toString(Charsets.UTF_8)

            // Scan plaintext manifest for the vault's 24 words and the full 2048-word BIP-39 list
            assertEquals(
                "$label manifest must contain no vault words",
                emptyList<String>(),
                BackupManifest.scanWalletWords(plaintextManifest, vaultWords),
            )
            assertEquals(
                "$label manifest must contain no BIP-39 word list entries",
                emptyList<String>(),
                BackupManifest.scanWalletWords(plaintextManifest, bip39Words),
            )
            assertTrue(
                "$label manifest text must not contain any vault word substring",
                vaultWords.none { plaintextManifest.contains(it) },
            )

            // Never pairing credentials or media
            assertFalse(plaintextManifest.contains("jwt-secret-token"))
            assertFalse(plaintextManifest.contains("phantom_pair_v1"))
            assertFalse(plaintextManifest.contains("phantom_solana_vault"))
            assertFalse(plaintextManifest.contains("PH-FCH:"))

            // Verify all allowed sections round-tripped accurately
            val restored = BackupFormat.openManifest(ByteArrayInputStream(bytes), pass12.copyOf())
            assertNotNull(restored)
            restored!!
            assertEquals(listOf("root", "claims", "roster", "chats", "mem", "settings", "profiles", "web_kv", "self_kv", "local_kv"), restored.entries)
            assertEquals(identity.id, restored.root?.id)
            assertEquals(identity.publicKeyB64, restored.root?.edPub)
            assertEquals(claimKey.publicKeyB64, restored.claims?.edPub)

            val restoredPeers = restored.roster.peers()
            assertEquals(1, restoredPeers.size)
            assertEquals(personPeer.id, restoredPeers.single().id)
            assertNull("avatar media thumb must be stripped", restoredPeers.single().avatarThumbB64)

            val restoredMsgs = restored.chats.messages()
            assertEquals(1, restoredMsgs.size)
            assertEquals(textMessage.id, restoredMsgs.single().id)

            val restoredSaved = restored.chats.savedNotes()
            assertEquals(1, restoredSaved.size)
            assertEquals(savedNote.id, restoredSaved.single().id)

            assertEquals(1, restored.mem.items().size)
            assertEquals("mem-001", restored.mem.items().single()["id"]?.let { (it as JsonPrimitive).content })

            val restoredSettings = restored.settings.typed()
            assertEquals(setOf("phantom_quiet_hours_v1", "phantom_theme_prefs"), restoredSettings.keys)
            assertEquals("Dark", restoredSettings["phantom_theme_prefs"]?.get("ui_theme_mode"))
            assertEquals(null, restoredSettings["phantom_theme_prefs"]?.get("app_night_mode_set"))
            assertEquals(true, restoredSettings["phantom_quiet_hours_v1"]?.get("on"))
            assertEquals(setOf("aa11"), restoredSettings["phantom_quiet_hours_v1"]?.get("except"))
        }

        val containerSrc = File(companionRoot(), "app/src/main/java/local/phantom/companion/data/AppContainer.kt").readText()
        assertTrue("AppContainer must wire BackupKeyStore", containerSrc.contains("BackupKeyStore(applicationContext)"))
        val wiringSrc = File(companionRoot(), "app/src/main/java/local/phantom/companion/data/backup/BackupWiring.kt").readText()
        for (forbidden in listOf("SolanaLayer", "BuiltInVault", "phantom_solana", "PairedDeviceStore", "phantom_pair", "peerFiles")) {
            assertFalse("BackupWiring must never touch $forbidden", wiringSrc.contains(forbidden))
        }
    }

    private class DeterministicStream(
        private val totalBytes: Long,
        private val onChunk: () -> Unit = {},
    ) : InputStream() {
        private var pos = 0L
        private var sinceSample = 0L

        override fun read(): Int {
            if (pos >= totalBytes) return -1
            val b = byteAt(pos)
            pos++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= totalBytes) return -1
            val count = minOf(len.toLong(), totalBytes - pos).toInt()
            var p = pos
            for (i in 0 until count) {
                b[off + i] = byteAt(p).toByte()
                p++
            }
            pos = p
            sinceSample += count
            if (sinceSample >= 4L * 1024L * 1024L) {
                sinceSample = 0L
                onChunk()
            }
            return count
        }

        companion object {
            fun byteAt(index: Long): Int =
                ((index * 31L + (index ushr 16) * 17L + 7L) and 0xFFL).toInt()
        }
    }

    private class VerifyingOutputStream(
        private val expectedTotal: Long,
        private val onChunk: () -> Unit = {},
    ) : OutputStream() {
        var count = 0L
            private set
        private var sinceSample = 0L

        override fun write(b: Int) {
            val expected = DeterministicStream.byteAt(count)
            if ((b and 0xFF) != expected) {
                fail("Byte mismatch at offset $count: expected $expected, got ${b and 0xFF}")
            }
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var p = count
            for (i in 0 until len) {
                val actual = b[off + i].toInt() and 0xFF
                val expected = DeterministicStream.byteAt(p)
                if (actual != expected) {
                    fail("Byte mismatch at offset $p: expected $expected, got $actual")
                }
                p++
            }
            count = p
            check(count <= expectedTotal) { "Received more bytes ($count) than expected ($expectedTotal)" }
            sinceSample += len
            if (sinceSample >= 4L * 1024L * 1024L) {
                sinceSample = 0L
                onChunk()
            }
        }
    }

    @Test
    fun `6 a header asking for a huge Argon2 cost is refused before any work`() {
        val sealed = ByteArrayOutputStream()
        BackupFormat.seal(ByteArrayInputStream(ByteArray(10)), sealed, pass12.copyOf(), BackupFormat.KdfParams(m = 8_192, t = 1, p = 1))
        val text = sealed.toByteArray().toString(Charsets.ISO_8859_1)
        for ((from, to) in listOf("\"m\":8192" to "\"m\":4000000", "\"t\":1" to "\"t\":500", "\"p\":1" to "\"p\":64")) {
            assertTrue("header lacks $from", text.contains(from))
            val hostile = text.replaceFirst(from, to).toByteArray(Charsets.ISO_8859_1)
            val started = System.nanoTime()
            assertFalse(BackupFormat.open(ByteArrayInputStream(hostile), ByteArrayOutputStream(), pass12.copyOf()))
            assertTrue("$to took too long", System.nanoTime() - started < 1_000_000_000L)
        }
    }

    @Test
    fun `7 no encrypted prefs store is read as plain settings`() {
        assertEquals(emptyList<String>(), BackupManifest.ALLOWED_SETTINGS_STORES.filter { it.endsWith("_enc") })
    }

    @Test
    fun `8 profiles section encodes and decodes in manifest round trip`() {
        val entry = BackupManifest.ProfileEntry(
            id = "prof-test-1",
            displayName = "Особистий",
            role = "ROOT",
            createdAtEpochMs = 1729900000000L,
            pinHashArgon2 = "hash123",
            biometricEnrolled = true,
            familiarRarity = "OFTEN",
            trustScore = 0.95f,
            isActive = true,
        )
        val manifest = BackupManifest.collect(
            identity = null,
            claimKey = null,
            profiles = listOf(entry),
        )
        assertTrue(manifest.entries.contains("profiles"))
        val encoded = manifest.encode()
        val decoded = BackupManifest.decode(encoded)
        assertNotNull(decoded)
        val restored = decoded!!.profiles.profiles()
        assertEquals(1, restored.size)
        assertEquals("prof-test-1", restored[0].id)
        assertEquals("Особистий", restored[0].displayName)
        assertEquals("ROOT", restored[0].role)
        assertTrue(restored[0].isActive)
    }
}
