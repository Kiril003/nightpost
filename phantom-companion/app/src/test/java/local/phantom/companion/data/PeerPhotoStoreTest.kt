package local.phantom.companion.data

import java.io.File
import java.nio.file.Files
import local.phantom.companion.core.net.peer.PeerProfile
import local.phantom.companion.ui.messenger.Served
import local.phantom.companion.ui.messenger.WebMessengerAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerPhotoStoreTest {

    private val store = PeerPhotoStore(Files.createTempDirectory("faces").toFile())
    private val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray() + ByteArray(64)

    private fun frame(rev: Long, photo: PeerProfile.Field<ByteArray>) =
        PeerProfile.encode(PeerProfile.Body(rev = rev, photo = photo))!!

    @Test
    fun `фото лягає, прибране — зникає, відсутнє — лишає`() {
        store.absorb("ana", frame(1, PeerProfile.Field.Set(webp)))
        val f = store.file(PeerPhotoStore.nameOf("ana"))!!
        assertTrue(f.readBytes().contentEquals(webp))
        store.absorb("ana", frame(2, PeerProfile.Field.Absent))
        assertTrue(store.has("ana"))
        store.absorb("ana", frame(3, PeerProfile.Field.Cleared))
        assertFalse(store.has("ana"))
    }

    @Test
    fun `не-зображення не лягає`() {
        store.absorb("ana", frame(1, PeerProfile.Field.Set("<svg onload=x>".toByteArray())))
        assertFalse(store.has("ana"))
    }

    @Test
    fun `імʼя з адреси — лише 32 hex`() {
        assertNull(store.file("../../shared_prefs/x"))
        assertNull(store.file("me"))
    }

    @Test
    fun `адреса обличчя доходить до перехоплювача, а не до index-html`() {
        val served = WebMessengerAssets.resolve("${WebMessengerAssets.ORIGIN}/webmsgr/_faces/${PeerPhotoStore.nameOf("ana")}?v=3") { null }
        assertEquals(Served.Face(PeerPhotoStore.nameOf("ana")), served)
    }

    @Test
    fun `фото приймається лише після того, як реєстр прийняв кадр`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val container = File(dir!!, "app/src/main/java/local/phantom/companion/data/AppContainer.kt").readText()
        assertTrue(container.contains("val applied = runCatching { peers.absorbProfile(message.fromId, message.body) }.getOrDefault(false)"))
        assertTrue(container.contains("if (applied) runCatching { peerPhotos.absorb(message.fromId, message.body) }"))
        val service = File(dir, "app/src/main/java/local/phantom/companion/service/PeerMeshService.kt").readText()
        assertTrue("сітка стартує без глашатая — новим контактам фото не дійде", service.contains("container.profileHerald"))
    }
}
