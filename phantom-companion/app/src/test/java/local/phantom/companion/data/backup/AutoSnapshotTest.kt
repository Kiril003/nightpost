package local.phantom.companion.data.backup

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.Bip39
import local.phantom.companion.core.solana.WalletReturn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AutoSnapshotTest {

    private val key = AutoSnapshot.newKey()

    private fun companionRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "feature-stream").isDirectory }
            ?: error("phantom-companion root not found")

    private val words: List<String> by lazy {
        File(companionRoot(), "core-solana/src/main/assets/bip39_english.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun dirs(): Pair<File, File> {
        val root = Files.createTempDirectory("autosnap").toFile()
        return File(root, "files").apply { mkdirs() } to File(root, "no_backup").apply { mkdirs() }
    }

    private fun manifest(body: String = "привіт") = BackupManifest.collect(
        identity = null,
        claimKey = null,
        peers = listOf(PeerIdentity(id = "1122334455667788", name = "Оля", kind = PeerIdentity.Kind.PERSON, pubKey = "cHVi")),
        messages = listOf(PeerMessage(id = "m1", fromId = "me", toId = "1122334455667788", body = body, sentAtMs = 1L, state = PeerMessage.State.DELIVERED)),
    )

    private fun snap(m: BackupManifest = manifest(), wallet: String? = null) =
        AutoSnapshot.Snapshot(atMs = 5L, wallet = wallet, manifest = m.encode())

    @Test
    fun `wallet words inside a message keep the snapshot off the phone`() {
        val (files, noBackup) = dirs()
        val seed = Bip39(words).fromEntropy(ByteArray(16) { (it * 13 + 3).toByte() }).joinToString(" ")
        val out = AutoSnapshot.write(files, noBackup, snap(manifest("мої слова: $seed")), walletWords = words.toSet(), key)
        assertEquals(AutoSnapshot.Written.REFUSED, out)
        assertFalse(AutoSnapshot.file(files).exists())
    }

    @Test
    fun `a keystore wrapped keyset keeps the snapshot off the phone`() {
        val (files, noBackup) = dirs()
        val blob = """{"__androidx_security_crypto_encrypted_prefs_key_keyset__":"12a901c3"}"""
        val out = AutoSnapshot.write(files, noBackup, snap(manifest(blob)), walletWords = words.toSet(), key)
        assertEquals(AutoSnapshot.Written.REFUSED, out)
        assertFalse(AutoSnapshot.file(files).exists())
    }

    @Test
    fun `a phone without a screen lock still gets a sealed copy`() {
        val (files, noBackup) = dirs()
        assertEquals(AutoSnapshot.Written.OK, AutoSnapshot.write(files, noBackup, snap(), words.toSet(), key))
        assertTrue(AutoSnapshot.open(files, key) is AutoSnapshot.Opened.Ok)
    }

    @Test
    fun `no word list means no snapshot`() {
        val (files, noBackup) = dirs()
        assertEquals(AutoSnapshot.Written.REFUSED, AutoSnapshot.write(files, noBackup, snap(), emptySet(), key))
    }

    @Test
    fun `ordinary chats pass and come back without a key`() {
        val (files, noBackup) = dirs()
        assertEquals(AutoSnapshot.Written.OK, AutoSnapshot.write(files, noBackup, snap(wallet = "So1dAddr"), words.toSet(), key))
        val read = AutoSnapshot.read(files, key)
        assertNotNull(read)
        assertEquals("So1dAddr", read!!.wallet)
        val target = object : RestoreTarget {
            var plan: RestorePlan? = null
            var keyKept = false
            override var restorePending = false
            override fun hasUserData() = false
            override fun write(plan: RestorePlan) { this.plan = plan }
            override fun keepKey(key: BackupFormat.SealedKey, uri: String?) { keyKept = true }
        }
        val res = restoreInto(BackupManifest.decode(read.manifest)!!, target)
        assertEquals(RestoreResult.Success(1, 1, 0), res)
        assertEquals("привіт", target.plan!!.messages.single().body)
        assertFalse(target.keyKept)
    }

    @Test
    fun `only a snapshot the system put back counts as a reinstall`() {
        val (files, noBackup) = dirs()
        val first = manifest()
        assertFalse(AutoSnapshot.pending(files, noBackup))
        AutoSnapshot.write(files, noBackup, snap(first), words.toSet(), key)
        assertFalse(AutoSnapshot.pending(files, noBackup))

        val (_, freshNoBackup) = dirs()
        assertTrue(AutoSnapshot.pending(files, freshNoBackup))
        assertEquals(AutoSnapshot.Written.SKIPPED, AutoSnapshot.write(files, freshNoBackup, snap(manifest("порожньо")), words.toSet(), key))
        assertEquals(first.encode(), AutoSnapshot.read(files, key)!!.manifest)
        AutoSnapshot.settle(freshNoBackup)
        assertFalse(AutoSnapshot.pending(files, freshNoBackup))
    }

    @Test
    fun `the old wallet is missing until the same address is back`() {
        assertFalse(WalletReturn.missing(null, "B"))
        assertTrue(WalletReturn.missing("A", "B"))
        assertTrue(WalletReturn.missing("A", null))
        assertFalse(WalletReturn.missing("A", "A"))
    }

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(companionRoot(), path)).documentElement

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `cloud backup carries only the sealed snapshot, with or without a screen lock`() {
        val rules = xml("app/src/main/res/xml/backup_rules.xml")
        val cloud = rules.children("cloud-backup").single()
        assertEquals("", cloud.getAttribute("disableIfNoEncryptionCapabilities"))
        val includes = cloud.children("include")
        assertEquals(1, includes.size)
        assertEquals("file", includes.single().getAttribute("domain"))
        assertEquals("${AutoSnapshot.DIR}/${AutoSnapshot.FILE}", includes.single().getAttribute("path"))
        assertEquals("", includes.single().getAttribute("requireFlags"))

        val transfer = rules.children("device-transfer").single()
        assertTrue(transfer.children("include").isEmpty())
        val excluded = transfer.children("exclude").map { it.getAttribute("domain") }.toSet()
        assertTrue(excluded.containsAll(setOf("root", "file", "database", "sharedpref", "external")))

        val legacy = xml("app/src/main/res/xml/backup_rules_legacy.xml")
        val legacyIncludes = legacy.children("include")
        assertEquals(1, legacyIncludes.size)
        assertEquals("${AutoSnapshot.DIR}/${AutoSnapshot.FILE}", legacyIncludes.single().getAttribute("path"))
        assertEquals("", legacyIncludes.single().getAttribute("requireFlags"))

        val app = xml("app/src/main/AndroidManifest.xml").children("application").single()
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals("true", app.getAttributeNS(ns, "allowBackup"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(ns, "dataExtractionRules"))
        assertEquals("@xml/backup_rules_legacy", app.getAttributeNS(ns, "fullBackupContent"))
    }

    @Test
    fun `an oversized snapshot drops photos and the oldest letters, not everything`() {
        val msgs = (1..40).map { i ->
            PeerMessage(id = "m$i", fromId = "me", toId = "1122334455667788", body = "лист $i " + "x".repeat(2_000), sentAtMs = (41 - i).toLong(), state = PeerMessage.State.DELIVERED)
        }
        val big = BackupManifest.collect(identity = null, claimKey = null, messages = msgs, selfFiles = mapOf("photo.img" to ByteArray(200_000)))
        val limit = 40_000
        val fitted = AutoSnapshot.fit(big, limit)
        assertTrue(fitted.encode().length <= limit)
        assertTrue(fitted.selfFiles.filesB64.isEmpty())
        val kept = fitted.chats.messages()
        assertTrue(kept.isNotEmpty())
        assertTrue(kept.any { it.id == "m1" })
        assertFalse(kept.any { it.id == "m40" })
    }

    @Test
    fun `nothing else lands in the snapshot directory`() {
        val (files, noBackup) = dirs()
        AutoSnapshot.write(files, noBackup, snap(), words.toSet(), key)
        assertEquals(listOf(AutoSnapshot.FILE), File(files, AutoSnapshot.DIR).list()!!.toList())
        assertNull(File(files, AutoSnapshot.DIR).listFiles()!!.firstOrNull { it.name.endsWith(".tmp") })
    }

    private fun bytesOf(files: File) = AutoSnapshot.file(files).readBytes()

    private fun ByteArray.has(needle: String): Boolean {
        val hay = String(this, Charsets.ISO_8859_1)
        val forms = listOf(needle, java.util.Base64.getEncoder().encodeToString(needle.toByteArray()).dropLast(4))
        return forms.any { hay.contains(String(it.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)) }
    }

    @Test
    fun `the file on the phone and in the cloud holds only ciphertext and the key id`() {
        val (files, noBackup) = dirs()
        val secrets = listOf("edPriv-SECRET-ed25519-private", "claim-SECRET-key", "\$argon2id\$v=19\$m=65536,t=3,p=1\$pinhash", "моє-приватне-повідомлення")
        assertEquals(AutoSnapshot.Written.OK, AutoSnapshot.write(files, noBackup, snap(manifest(secrets.joinToString(" "))), words.toSet(), key))
        val bytes = bytesOf(files)
        secrets.forEach { assertFalse(it, bytes.has(it)) }
        assertFalse(bytes.has("1122334455667788"))
        val fields = kotlinx.serialization.json.Json.parseToJsonElement(String(bytes)).let { it as kotlinx.serialization.json.JsonObject }.keys
        assertEquals(setOf("v", "kid", "iv", "ct"), fields)
    }

    @Test
    fun `the same key opens the copy, another key or none leaves an honest state`() {
        val (files, noBackup) = dirs()
        AutoSnapshot.write(files, noBackup, snap(manifest("до зустрічі")), words.toSet(), key)
        val back = AutoSnapshot.open(files, key) as AutoSnapshot.Opened.Ok
        assertEquals("до зустрічі", BackupManifest.decode(back.snapshot.manifest)!!.chats.messages().single().body)
        assertEquals(AutoSnapshot.Opened.NoKey, AutoSnapshot.open(files, null))
        assertEquals(AutoSnapshot.Opened.NoKey, AutoSnapshot.open(files, AutoSnapshot.newKey()))
        AutoSnapshot.file(files).writeText("{\"v\":1,\"at_ms\":5,\"manifest\":\"x\"}")
        assertEquals(AutoSnapshot.Opened.Unreadable, AutoSnapshot.open(files, key))
    }

    @Test
    fun `no key means no file at all`() {
        val (files, noBackup) = dirs()
        AutoSnapshot.write(files, noBackup, snap(), words.toSet(), key)
        assertEquals(AutoSnapshot.Written.NO_KEY, AutoSnapshot.write(files, noBackup, snap(), words.toSet(), null))
        assertFalse(AutoSnapshot.file(files).exists())
    }

    @Test
    fun `turning cloud backup off removes the file`() {
        val (files, noBackup) = dirs()
        AutoSnapshot.write(files, noBackup, snap(), words.toSet(), key)
        assertEquals(AutoSnapshot.Written.OFF, AutoSnapshot.write(files, noBackup, snap(), words.toSet(), key, enabled = false))
        assertFalse(AutoSnapshot.file(files).exists())
    }

    @Test
    fun `a copy that could not be put back is never overwritten by the empty new install`() {
        val (files, oldNoBackup) = dirs()
        AutoSnapshot.write(files, oldNoBackup, snap(manifest("усе моє")), words.toSet(), key)
        val before = bytesOf(files)
        val freshNoBackup = File(oldNoBackup.parentFile, "fresh").apply { mkdirs() }
        assertTrue(AutoSnapshot.pending(files, freshNoBackup))

        val res = AutoSnapshot.restore(files, freshNoBackup, key = null) { error("must not apply without a key") }
        assertEquals(RestoreResult.Error(RestoreResult.Reason.INVALID), res)
        assertFalse(AutoSnapshot.pending(files, freshNoBackup))
        assertTrue(AutoSnapshot.held(freshNoBackup))
        assertEquals(AutoSnapshot.Written.SKIPPED, AutoSnapshot.write(files, freshNoBackup, snap(manifest("порожньо")), words.toSet(), key))
        assertTrue(before.contentEquals(bytesOf(files)))

        val notEmpty = File(oldNoBackup.parentFile, "busy").apply { mkdirs() }
        assertEquals(RestoreResult.NotEmpty, AutoSnapshot.restore(files, notEmpty, key) { RestoreResult.NotEmpty })
        assertTrue(AutoSnapshot.held(notEmpty))

        AutoSnapshot.release(freshNoBackup)
        assertEquals(AutoSnapshot.Written.OK, AutoSnapshot.write(files, freshNoBackup, snap(manifest("нова")), words.toSet(), key))
    }

    @Test
    fun `a restored copy settles without holding`() {
        val (files, oldNoBackup) = dirs()
        AutoSnapshot.write(files, oldNoBackup, snap(), words.toSet(), key)
        val fresh = File(oldNoBackup.parentFile, "fresh").apply { mkdirs() }
        val res = AutoSnapshot.restore(files, fresh, key) { RestoreResult.Success(1, 1, 0) }
        assertEquals(RestoreResult.Success(1, 1, 0), res)
        assertFalse(AutoSnapshot.held(fresh))
        assertFalse(AutoSnapshot.pending(files, fresh))
    }

    private class NoPlayServices(var cloudKey: ByteArray? = null, val cloudWorks: Boolean = false) : SnapshotKeys {
        var mine: ByteArray? = null
        override fun local() = mine
        override fun cloud(): ByteArray? = if (cloudWorks) cloudKey else throw IllegalStateException("no Google Play services")
        override fun keepLocal(key: ByteArray) { mine = key }
        override fun keepCloud(key: ByteArray): Boolean = cloudWorks.also { if (it) cloudKey = key }
        override fun forget() { mine = null; cloudKey = null }
    }

    @Test
    fun `a phone without Google Play services still seals the copy with its own key`() {
        val keys = NoPlayServices()
        val first = keys.pick()
        assertEquals(AutoSnapshot.KEY_BYTES, first.key.size)
        assertFalse(first.inCloud)
        val again = keys.pick()
        assertTrue(again.key.contentEquals(first.key))
        assertFalse(again.inCloud)

        val (files, noBackup) = dirs()
        assertEquals(AutoSnapshot.Written.OK, AutoSnapshot.write(files, noBackup, snap(), words.toSet(), first.key))
        val hay = String(bytesOf(files), Charsets.ISO_8859_1)
        assertFalse(hay.contains(String(first.key, Charsets.ISO_8859_1)))
        listOf(java.util.Base64.getEncoder(), java.util.Base64.getUrlEncoder()).forEach {
            assertFalse(hay.contains(it.encodeToString(first.key).trimEnd('=')))
        }
        assertTrue(AutoSnapshot.open(files, first.key) is AutoSnapshot.Opened.Ok)
    }

    @Test
    fun `a new phone takes the key from Block Store and keeps it locally`() {
        val old = AutoSnapshot.newKey()
        val keys = NoPlayServices(cloudKey = old, cloudWorks = true)
        val picked = keys.pick()
        assertTrue(picked.inCloud)
        assertTrue(picked.key.contentEquals(old))
        assertTrue(keys.mine!!.contentEquals(old))
    }

    @Test
    fun `a phone without a screen lock says the copy is weakly protected and offers the lock`() {
        assertEquals(CloudBackupState.WEAK_NO_LOCK, cloudBackupState(on = true, held = false, deviceSecure = false, cloudKey = true))
        assertEquals(CloudBackupState.WEAK_NO_LOCK, cloudBackupState(on = true, held = false, deviceSecure = false, cloudKey = false))
        assertEquals(CloudBackupState.OK, cloudBackupState(on = true, held = false, deviceSecure = true, cloudKey = true))
        assertEquals(CloudBackupState.PHONE_ONLY, cloudBackupState(on = true, held = false, deviceSecure = true, cloudKey = false))
        assertEquals(CloudBackupState.OFF, cloudBackupState(on = false, held = false, deviceSecure = false, cloudKey = true))
        assertEquals(CloudBackupState.HELD, cloudBackupState(on = true, held = true, deviceSecure = false, cloudKey = true))
        val ui = File(generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "feature-stream").isDirectory },
            "app/src/main/java/local/phantom/companion/ui/home/ProfileSections.kt").readText()
        assertTrue(ui.contains("CloudBackupState.WEAK_NO_LOCK -> stringResource(R.string.cloud_backup_weak)"))
        assertTrue(ui.contains("Settings.ACTION_SECURITY_SETTINGS"))
    }
}
