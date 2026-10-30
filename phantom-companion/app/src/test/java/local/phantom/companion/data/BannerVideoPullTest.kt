package local.phantom.companion.data

import java.io.File
import java.nio.file.Files
import local.phantom.companion.core.net.peer.PeerBanner
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerProfile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T53/T63: інші бачили лише перший кадр відео-банера — петля не виїжджала з
 * телефона. Тепер глядач просить, власник віддає шматками, глядач складає.
 */
class BannerVideoPullTest {

    private val owner = "owner_node"
    private val viewer = "viewer_node"
    private val clip = ByteArray(PeerFile.CHUNK_BYTES * 3 + 5) { (it * 7 % 253).toByte() }
    private val video = PeerProfile.BannerVideo(PeerFile.sha256Hex(clip), clip.size)
    private val spec = PeerProfile.Banner(
        listOf(PeerProfile.BannerItem("00112233445566778899aabbccddeeff", 1000)),
        videoStill = true,
        video = video,
    ).spec()
    private val dirs = mutableListOf<File>()

    private fun tmp(): File = Files.createTempDirectory("bvp").toFile().also { dirs += it }

    @After fun clean() = dirs.forEach { it.deleteRecursively() }

    private class Wire {
        val single = mutableListOf<Pair<String, String>>()
        val many = mutableListOf<Pair<String, List<String>>>()
    }

    private fun ownerSide(wire: Wire, contacts: Set<String> = setOf(viewer), now: () -> Long = { 0L }): BannerVideoPull {
        val home = tmp()
        val file = File(home, "video").apply { writeBytes(clip) }
        return BannerVideoPull(
            files = PeerFileStore(home) { null },
            own = { video to file },
            specOf = { null },
            specs = { emptyList() },
            mayServe = { it in contacts },
            send = { id, b -> wire.single += id to b; true },
            sendMany = { id, b -> wire.many += id to b },
            nowMs = now,
        )
    }

    @Test fun viewerAsksOwnerServesViewerAssembles() {
        val wire = Wire()
        val viewerFiles = arrayOfNulls<PeerFileStore>(1)
        lateinit var viewerPull: BannerVideoPull
        viewerFiles[0] = PeerFileStore(tmp()) { viewerPull.manifest(it) }
        viewerPull = BannerVideoPull(
            files = viewerFiles[0]!!,
            own = { null },
            specOf = { if (it == owner) spec else null },
            specs = { listOf(spec) },
            mayServe = { false },
            send = { id, b -> wire.single += id to b; true },
            sendMany = { _, _ -> },
        )

        assertEquals(BannerVideoPull.State.ASKED, viewerPull.ask(owner))
        val (to, frame) = wire.single.single()
        assertEquals(owner, to)
        assertEquals(video.blobId, PeerBanner.pulled(frame))

        val ownerWire = Wire()
        assertTrue(ownerSide(ownerWire).serve(PeerMessage("q1", viewer, owner, frame, 1L)))
        val (dest, bodies) = ownerWire.many.single()
        assertEquals(viewer, dest)
        bodies.forEachIndexed { i, b -> viewerFiles[0]!!.absorb(PeerMessage("c$i", owner, viewer, b, 2L + i), viewer) }

        assertArrayEquals(clip, viewerFiles[0]!!.bytes(video.blobId))
        assertEquals(BannerVideoPull.State.READY, viewerPull.ask(owner))
        assertEquals(1, wire.single.size)
    }

    @Test fun ownerServesOnlyContactsCurrentClipAndNotTwiceInARow() {
        val wire = Wire()
        var now = 0L
        val pull = ownerSide(wire, now = { now })
        val ask = PeerBanner.pull(video.blobId)!!
        assertFalse(pull.serve(PeerMessage("s", "stranger", owner, ask, 1L)))
        assertFalse(pull.serve(PeerMessage("w", viewer, owner, PeerBanner.pull("ab".repeat(16))!!, 1L)))
        assertTrue(pull.serve(PeerMessage("a", viewer, owner, ask, 1L)))
        assertFalse(pull.serve(PeerMessage("b", viewer, owner, ask, 2L)))
        now = 11 * 60_000L
        assertTrue(pull.serve(PeerMessage("c", viewer, owner, ask, 3L)))
        assertEquals(2, wire.many.size)
    }
}
