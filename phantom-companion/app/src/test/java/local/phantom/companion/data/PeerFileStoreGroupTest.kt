package local.phantom.companion.data

import java.io.File
import java.nio.file.Files
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * T74: у групі отримувач бачив фото «ще завантажується», а голосове — «ще
 * вантажиться». Шматки приїжджали, але опис лежав під конвертом `PH-GRP:`, і
 * сховище не впізнавало його — файл не складався ніколи.
 */
class PeerFileStoreGroupTest {

    private val me = "me_node"
    private val alice = "alice_node"
    private val group = PeerGroup.Group("g1", "Krompachy", listOf(alice, me, "bob_node"))
    private val cipher = ByteArray(PeerFile.CHUNK_BYTES * 2 + 77) { (it % 251).toByte() }
    private val blobId = "ab".repeat(16)

    private fun descriptor(): String =
        """{"name":"1000062282.jpg","size":${cipher.size - 16},"mime":"image/jpeg","sha256":"${PeerFile.sha256Hex(cipher)}","blob_id":"$blobId","key_hex":"${"11".repeat(32)}","nonce_hex":"${"22".repeat(12)}"}"""

    private fun chunks(): List<PeerMessage> {
        val total = PeerFile.chunkCount(cipher.size)
        return (0 until total).map { i ->
            PeerMessage("c$i", alice, me, PeerFile.encodeChunk(blobId, i, total, PeerFile.sliceAt(cipher, i)!!), 10L + i)
        }
    }

    private fun withStore(log: MutableList<PeerMessage>, block: (PeerFileStore, File) -> Unit) {
        val dir = Files.createTempDirectory("pfs-group").toFile()
        try {
            block(PeerFileStore(dir) { PeerFileStore.manifestIn(log, it) }, dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun groupPhotoAssemblesWhenChunksArriveFirst() {
        val log = mutableListOf<PeerMessage>()
        withStore(log) { store, _ ->
            chunks().forEach { log += it; store.absorb(it, me) }
            val line = PeerMessage("d1", alice, me, PeerGroup.encode(group, descriptor()), 9L)
            log += line
            store.absorb(line, me)

            val att = PeerFile.carried(line.body)!!
            assertEquals(PeerFileStore.Fate.READY, store.fate(alice, att))
            assertArrayEquals(cipher, store.bytes(blobId))
        }
    }

    @Test
    fun groupVoiceInSpaceChannelAssemblesWhenDescriptorArrivesFirst() {
        val log = mutableListOf<PeerMessage>()
        withStore(log) { store, _ ->
            val voice = descriptor().replace("\"mime\":\"image/jpeg\"", "\"mime\":\"audio/webm\",\"kind\":\"voice\"")
            val line = PeerMessage("d1", alice, me, PeerGroup.encode(group, PeerGroup.inChannel("general", voice)!!), 9L)
            log += line
            store.absorb(line, me)
            chunks().forEach { log += it; store.absorb(it, me) }

            assertNotNull(store.blob(blobId))
            assertEquals(PeerFile.VOICE, PeerFile.carried(line.body)!!.kind)
        }
    }

    @Test
    fun partsLeftByOlderBuildSettleOnRetry() {
        val log = mutableListOf<PeerMessage>()
        withStore(log) { store, _ ->
            chunks().forEach { log += it; store.absorb(it, me) }
            // Старий білд: опис у журналі є, але складання на нього не спрацювало.
            log += PeerMessage("d1", alice, me, PeerGroup.encode(group, descriptor()), 9L)

            assertEquals(true, store.settle(blobId))
            assertArrayEquals(cipher, store.bytes(blobId))
        }
    }
}
