package local.phantom.companion.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupResealTest {

    @get:Rule val tmp = TemporaryFolder()

    private val pass = "correct-horse-battery".toCharArray()
    private val cheap = BackupFormat.KdfParams(m = 8_192, t = 1, p = 1)

    private fun manifest(id: String) = BackupManifest.collect(
        identity = null,
        claimKey = null,
        messages = listOf(PeerMessage(id = id, fromId = "a", toId = "b", body = "x", sentAtMs = 1L, state = PeerMessage.State.DELIVERED)),
    )

    private class Doc(var bytes: ByteArray, var failAfter: Int = -1) : BackupDoc {
        override fun openRead(): InputStream = ByteArrayInputStream(bytes)
        override fun openWrite(): OutputStream {
            bytes = ByteArray(0)
            val limit = failAfter
            failAfter = -1
            return object : OutputStream() {
                val buf = ByteArrayOutputStream()
                override fun write(b: Int) {
                    if (limit >= 0 && buf.size() >= limit) throw IOException("storage gone")
                    buf.write(b)
                    bytes = buf.toByteArray()
                }
            }
        }
    }

    private fun setup(): Triple<BackupKeyStore, Doc, ByteArray> {
        val keys = BackupKeyStore.inMemory()
        val first = ByteArrayOutputStream()
        BackupFormat.sealManifest(manifest("old"), first, pass.copyOf(), cheap, keys)
        keys.setBackupUri("content://doc")
        keys.setLastBackupEpochMs(10L)
        return Triple(keys, Doc(first.toByteArray()), first.toByteArray())
    }

    private fun idsIn(bytes: ByteArray) =
        BackupFormat.openManifest(ByteArrayInputStream(bytes), pass.copyOf())?.chats?.messages()?.map { it.id }

    @Test
    fun `a good run replaces the file and keeps the previous generation`() {
        val (keys, doc, old) = setup()
        val out = resealGeneration(keys, tmp.root, { doc }, { BackupFormat.resealManifest(manifest("new"), it, keys) }, nowMs = 99L)
        assertEquals(ResealOutcome.DONE, out)
        assertEquals(listOf("new"), idsIn(doc.bytes))
        assertArrayEquals(old, tmp.root.resolve("reseal_prev.backup").readBytes())
        assertEquals(99L, keys.lastBackupEpochMs())
    }

    @Test
    fun `a failing seal never touches the only copy`() {
        val (keys, doc, old) = setup()
        val out = resealGeneration(keys, tmp.root, { doc }, { error("collect blew up") })
        assertEquals(ResealOutcome.RETRY, out)
        assertArrayEquals(old, doc.bytes)
        assertEquals(10L, keys.lastBackupEpochMs())
    }

    @Test
    fun `a write that dies halfway puts the previous generation back`() {
        val (keys, doc, old) = setup()
        doc.failAfter = 100
        val out = resealGeneration(keys, tmp.root, { doc }, { BackupFormat.resealManifest(manifest("new"), it, keys) })
        assertEquals(ResealOutcome.RETRY, out)
        assertEquals(listOf("old"), idsIn(doc.bytes))
    }

    @Test
    fun `a backup without its key stops claiming a date`() {
        val keys = BackupKeyStore.inMemory()
        keys.setBackupUri("content://doc")
        keys.setLastBackupEpochMs(10L)
        val out = resealGeneration(keys, tmp.root, { Doc(ByteArray(0)) }, { error("unused") })
        assertEquals(ResealOutcome.NOTHING, out)
        assertNull(keys.backupUri())
        assertEquals(0L, keys.lastBackupEpochMs())
    }

    @Test
    fun `nothing to do without a file`() {
        assertEquals(ResealOutcome.NOTHING, resealGeneration(BackupKeyStore.inMemory(), tmp.root, { null }, { error("unused") }))
    }
}
