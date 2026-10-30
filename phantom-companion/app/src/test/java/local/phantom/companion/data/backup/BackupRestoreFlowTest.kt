package local.phantom.companion.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreFlowTest {

    private val pass = "correct-horse-battery".toCharArray()
    private val cheap = BackupFormat.KdfParams(m = 8_192, t = 1, p = 1)

    private class Fake(var data: Boolean = false, var failWrite: Boolean = false) : RestoreTarget {
        val writes = mutableListOf<RestorePlan>()
        var key: BackupFormat.SealedKey? = null
        var uri: String? = null
        override var restorePending = false
        override fun hasUserData() = data
        override fun write(plan: RestorePlan) {
            if (failWrite) throw IllegalStateException("disk full")
            writes += plan
            data = true
        }
        override fun keepKey(key: BackupFormat.SealedKey, uri: String?) {
            this.key = key
            this.uri = uri
        }
    }

    private val manifest = BackupManifest.collect(
        identity = null,
        claimKey = null,
        peers = listOf(PeerIdentity(id = "1122334455667788", name = "Оля", kind = PeerIdentity.Kind.PERSON, pubKey = "cHVi")),
        messages = listOf(PeerMessage(id = "m1", fromId = "me", toId = "1122334455667788", body = "привіт", sentAtMs = 1L, state = PeerMessage.State.DELIVERED)),
    )

    private fun opened(m: BackupManifest = manifest): BackupFormat.Opened {
        val out = ByteArrayOutputStream()
        BackupFormat.sealManifest(m, out, pass.copyOf(), cheap)
        return BackupFormat.openForRestore(out.toByteArray(), pass.copyOf())!!
    }

    @Test
    fun `a damaged entry stops the restore before anything is written`() {
        val bad = manifest.copy(chats = manifest.chats.copy(msgsB64 = manifest.chats.msgsB64 + "e30"))
        val fake = Fake()
        val o = opened(bad)
        val res = restoreBackup(o.manifest, o.key, "content://x", fake)
        assertEquals(RestoreResult.Error(RestoreResult.Reason.INVALID), res)
        assertTrue(fake.writes.isEmpty())
        assertFalse(fake.restorePending)
    }

    @Test
    fun `a failed write can be retried instead of hitting not empty`() {
        val fake = Fake(failWrite = true)
        val o = opened()
        assertEquals(RestoreResult.Error(RestoreResult.Reason.WRITE), restoreBackup(o.manifest, o.key, "content://x", fake))
        assertTrue(fake.restorePending)
        fake.data = true
        fake.failWrite = false
        assertTrue(restoreBackup(o.manifest, o.key, "content://x", fake) is RestoreResult.Success)
        assertFalse(fake.restorePending)
    }

    @Test
    fun `someone's own data is never overwritten`() {
        val fake = Fake(data = true)
        val o = opened()
        assertEquals(RestoreResult.NotEmpty, restoreBackup(o.manifest, o.key, "content://x", fake))
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun `after restore the weekly reseal has its key and the file still opens with the old passphrase`() {
        val fake = Fake()
        val o = opened()
        assertTrue(restoreBackup(o.manifest, o.key, "content://x", fake) is RestoreResult.Success)
        val keys = BackupKeyStore.inMemory()
        keys.save(assertNotNullKey(fake.key))
        val resealed = ByteArrayOutputStream()
        assertNotNull(BackupFormat.resealManifest(manifest, resealed, keys))
        val back = BackupFormat.openManifest(ByteArrayInputStream(resealed.toByteArray()), pass.copyOf())
        assertEquals(listOf("m1"), back!!.chats.messages().map { it.id })
    }

    @Test
    fun `the manual copy keeps its key and file while the automatic restore leaves them alone`() {
        val auto = Fake()
        assertTrue(restoreInto(manifest, auto) is RestoreResult.Success)
        assertEquals(1, auto.writes.size)
        assertEquals(null, auto.key)
        val manual = Fake()
        val o = opened()
        assertTrue(restoreBackup(o.manifest, o.key, "content://x", manual) is RestoreResult.Success)
        assertEquals(listOf("m1"), manual.writes.single().messages.map { it.id })
        assertNotNullKey(manual.key)
        assertEquals("content://x", manual.uri)
    }

    private fun assertNotNullKey(k: BackupFormat.SealedKey?): BackupFormat.SealedKey {
        assertNotNull("restore must keep K for the weekly reseal", k)
        return k!!
    }
}
