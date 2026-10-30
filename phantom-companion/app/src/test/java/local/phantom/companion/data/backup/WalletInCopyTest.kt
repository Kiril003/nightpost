package local.phantom.companion.data.backup

import java.io.File
import java.nio.file.Files
import java.util.Base64
import local.phantom.companion.core.data.vault.VaultCipher
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.Bip39
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletInCopyTest {

    private val key = AutoSnapshot.newKey()

    private fun companionRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "feature-stream").isDirectory }
            ?: error("phantom-companion root not found")

    private val dict: List<String> by lazy {
        File(companionRoot(), "core-solana/src/main/assets/bip39_english.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val fixture: List<String> by lazy { Bip39(dict).fromEntropy(ByteArray(16) { (it * 7 + 1).toByte() }) }
    private val other: List<String> by lazy { Bip39(dict).fromEntropy(ByteArray(16) { (it * 11 + 5).toByte() }) }

    private object Plain : VaultCipher {
        override fun encrypt(plaintext: String) = "T:" + Base64.getEncoder().encodeToString(plaintext.reversed().toByteArray())
        override fun decrypt(stored: String) = String(Base64.getDecoder().decode(stored.removePrefix("T:"))).reversed()
    }

    private fun dirs(): Pair<File, File> {
        val root = Files.createTempDirectory("walletcopy").toFile()
        return File(root, "files").apply { mkdirs() } to File(root, "no_backup").apply { mkdirs() }
    }

    private fun manifest(body: String) = BackupManifest.collect(
        identity = null,
        claimKey = null,
        peers = listOf(PeerIdentity(id = "1122334455667788", name = "Оля", kind = PeerIdentity.Kind.PERSON, pubKey = "cHVi")),
        messages = listOf(PeerMessage(id = "m1", fromId = "me", toId = "1122334455667788", body = body, sentAtMs = 1L, state = PeerMessage.State.DELIVERED)),
    )

    private fun snap(body: String = "привіт", walletKey: List<String>? = fixture) =
        AutoSnapshot.Snapshot(atMs = 5L, wallet = "So1dAddr", manifest = manifest(body).encode(), walletKey = walletKey)

    private fun write(s: AutoSnapshot.Snapshot, secure: Boolean = true): Pair<AutoSnapshot.Written, File> {
        val (files, noBackup) = dirs()
        return AutoSnapshot.write(files, noBackup, s, dict.toSet(), key, deviceSecure = secure) to files
    }

    @Test
    fun `the wallet key rides only inside the sealed copy`() {
        val (out, files) = write(snap())
        assertEquals(AutoSnapshot.Written.OK, out)
        val raw = AutoSnapshot.file(files).readText()
        assertFalse(raw.contains(fixture.take(3).joinToString(" ")))
        assertFalse(AutoSnapshot.leaks(raw, dict.toSet()))
        val opened = AutoSnapshot.read(files, key)!!
        assertTrue(opened.walletKey == fixture)
        assertFalse(opened.manifest.contains(fixture.first() + " " + fixture[1]))
    }

    @Test
    fun `no screen lock leaves the wallet key out of the copy`() {
        val (out, files) = write(snap(), secure = false)
        assertEquals(AutoSnapshot.Written.OK, out)
        assertNull(AutoSnapshot.read(files, key)!!.walletKey)
    }

    @Test
    fun `the guard still refuses a stray word run anywhere else`() {
        assertEquals(AutoSnapshot.Written.REFUSED, write(snap(body = "мої слова: " + other.joinToString(" "))).first)
        assertEquals(AutoSnapshot.Written.REFUSED, write(snap(body = "fixture: " + fixture.joinToString(" "))).first)
        val marker = """{"__androidx_security_crypto_encrypted_prefs_key_keyset__":"12a901c3"}"""
        assertEquals(AutoSnapshot.Written.REFUSED, write(snap(body = marker)).first)
    }

    @Test
    fun `a wallet slot that is not a clean word list stays out`() {
        val (_, a) = write(snap(walletKey = listOf("not", "a", "seed")))
        assertNull(AutoSnapshot.read(a, key)!!.walletKey)
        val (_, b) = write(snap(walletKey = fixture.dropLast(1) + "__androidx_security_crypto_encrypted_prefs_key_keyset__"))
        assertNull(AutoSnapshot.read(b, key)!!.walletKey)
    }

    @Test
    fun `a restored copy hands the key to the wallet slot and nowhere else`() {
        val (out, files) = write(snap())
        assertEquals(AutoSnapshot.Written.OK, out)
        val newPhone = Files.createTempDirectory("np").toFile()
        val slot = WalletSlot(newPhone, Plain)
        val result = AutoSnapshot.restore(files, newPhone, key) { s ->
            s.walletKey?.let(slot::hold)
            RestoreResult.Success(0, 0, 0)
        }
        assertTrue(result is RestoreResult.Success)
        assertTrue(slot.held() == fixture)
        newPhone.listFiles()!!.filter { it.name.startsWith("wallet_slot") }.forEach {
            assertFalse(it.readText().contains(fixture.first()))
        }
        slot.keep(other)
        assertTrue(slot.forCopy() == fixture)
        slot.settle()
        assertNull(slot.held())
        assertTrue(slot.forCopy() == other)
        slot.drop()
        assertFalse(slot.has())
    }

    @Test
    fun `the Data row says plainly when the wallet is not in the copy`() {
        assertEquals(WalletInCopy.NO_LOCK, walletInCopy(hasWallet = true, on = true, held = false, secure = false, slot = true))
        assertEquals(WalletInCopy.NEEDS_WORDS, walletInCopy(hasWallet = true, on = true, held = false, secure = true, slot = false))
        assertEquals(WalletInCopy.IN, walletInCopy(hasWallet = true, on = true, held = false, secure = true, slot = true))
        assertEquals(WalletInCopy.NONE, walletInCopy(hasWallet = false, on = true, held = false, secure = false, slot = false))
        assertEquals(WalletInCopy.NONE, walletInCopy(hasWallet = true, on = false, held = false, secure = true, slot = true))
    }

    @Test
    fun `the words reach the copy only through the vault and back only through the wallet store`() {
        val root = companionRoot()
        val vault = File(root, "core-solana/src/main/java/local/phantom/companion/core/solana/BuiltInVault.kt").readText()
        assertTrue(vault.contains("box.open().split(' ').also(WalletEscrow::saw)"))
        assertTrue(vault.contains("box.seal(words.joinToString(\" \"))\n        WalletEscrow.saw(words)"))
        val card = File(root, "core-solana/src/main/java/local/phantom/companion/core/solana/WalletActivity.kt").readText()
        assertTrue(card.contains("restoreSeed(door, say) { SolanaLayer.restore(words) }"))
        val wiring = File(root, "app/src/main/java/local/phantom/companion/data/backup/AutoSnapshotWiring.kt").readText()
        assertTrue(wiring.contains("snap.walletKey?.let { AutoBackup.slotFor(app).hold(it) }"))
        assertTrue(wiring.contains("deviceSecure = secure"))
        assertTrue(File(root, "app/src/main/java/local/phantom/companion/data/ContainerBoot.kt").readText().contains("wireWalletEscrow()"))
        listOf(
            "app/src/main/java/local/phantom/companion/data/backup/WalletSlot.kt",
            "core-solana/src/main/java/local/phantom/companion/core/solana/WalletEscrow.kt",
        ).forEach { assertFalse(File(root, it).readText().contains(Regex("Log\\.|println|Timber"))) }
    }
}
