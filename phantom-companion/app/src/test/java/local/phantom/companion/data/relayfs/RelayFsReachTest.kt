package local.phantom.companion.data.relayfs

import java.io.File
import local.phantom.companion.core.net.peer.RelayFs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Конверт v5 доходить до живого насоса сховка — і лише за прапорцем. */
class RelayFsReachTest {

    private fun src(path: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        val f = File(dir ?: fail("корінь не знайдено") as Nothing, "app/src/main/java/local/phantom/companion/$path")
        assertTrue("$path не знайдено", f.isFile)
        return f.readText()
    }

    @Test fun `прапорець за замовчуванням вимкнено`() {
        assertFalse(RelayFs.Flag.enabled)
    }

    @Test fun `насос сітки отримує гачки v5, ключі ставить проводка, пачки — лише з прапорцем`() {
        val mesh = src("data/PeerMesh.kt")
        assertTrue(mesh.contains("fsSeal = { m -> relayFsSeal(m) }"))
        assertTrue(mesh.contains("fsOpen = { raw, peerId -> relayFsOpen(raw, peerId) }"))
        val relay = src("data/PeerMeshRelay.kt")
        assertTrue(relay.contains("override fun relayFsSeal(message: PeerMessage) = fsSeal(message)"))
        assertTrue(relay.contains("override fun relayFsOpen(raw: String, peerId: String): PeerMessage? = fsOpen(raw, peerId)"))
        val wiring = src("data/relayfs/RelayFsWiring.kt")
        assertTrue(wiring.contains("container.mesh.relayFsKeys = keys"))
        assertTrue(wiring.contains("if (RelayFs.Flag.enabled)"))
        assertTrue(wiring.contains("RelayFs.decodeBundle(m.body, ed)"))
        assertTrue(src("service/PeerMeshService.kt").contains("container.relayFs.start(scope)"))
    }

    @Test fun `повтор копії v5 доходить до насоса, а перемикач — до мосту`() {
        val mesh = src("data/PeerMesh.kt")
        assertTrue(mesh.contains("RelayFsMail.landed(raw, peerId, it)"))
        assertTrue(src("data/PeerMeshRelay.kt").contains("override fun relayFsLanded(raw: String, peerId: String): String? = fsLanded(raw, peerId)"))
        val bridge = src("ui/messenger/WebMessengerBridge.kt")
        assertTrue(bridge.contains("WebMessengerRelayFs(container.relayFs)"))
        assertTrue(bridge.contains("if (relayFsRoute.claims(seg)) return relayFsRoute.handle(method, seg)"))
        assertTrue(src("data/relayfs/RelayFsWiring.kt").contains("RelayFs.Flag.enabled = flagPrefs.getBoolean(FLAG_KEY, false)"))
    }
}
