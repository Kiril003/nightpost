package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Що вільно віддати вебу з диска.
 *
 * Перехоплювач у WebView до цієї хвилі вмів віддавати лише бандл із `assets`.
 * Тепер він віддає ще й байти з каталогу застосунку — і це рівно те місце, де
 * помилка в розборі шляху означає віддати чуже. Перевіряється не «чи відсіяно
 * `..`», а сильніше: чи взагалі можливо виразити щось, крім одного блоба.
 */
class PeerFileRouteTest {

    private val blob = PeerFile.newBlobId()

    @Test
    fun `свій шлях читається`() {
        assertEquals(blob, PeerFileRoute.blobOf("_files/$blob"))
        assertTrue(PeerFileRoute.isFileRequest("_files/$blob"))
    }

    @Test
    fun `вихід за межі каталогу не виражається взагалі`() {
        // Кожен рядок тут — спроба, яку роблять насправді.
        val tries = listOf(
            "_files/..",
            "_files/../../../data/data/local.phantom.companion/databases/keys.db",
            "_files/..%2F..%2Fsecret",
            "_files/./$blob",
            "_files/$blob/../$blob",
            "_files/sub/$blob",
            "_files/",
            "_files/$blob ",
            "_files/$blob.jpg",
            "_files/${blob.uppercase()}",
        )
        for (t in tries) {
            assertNull("шлях «$t» не мав дати блоба", PeerFileRoute.blobOf(t))
        }
    }

    @Test
    fun `чужий префікс — не наша справа`() {
        assertFalse(PeerFileRoute.isFileRequest("assets/index.js"))
        assertFalse(PeerFileRoute.isFileRequest(""))
        assertNull(PeerFileRoute.blobOf("index.html"))
        assertNull(PeerFileRoute.blobOf("files/$blob"))
    }

    @Test
    fun `шлях на диску складається лише з перевіреного`() {
        assertEquals("msgr/aaaa1111/$blob", PeerFileRoute.relativePath("aaaa1111", blob))
        assertNull(PeerFileRoute.relativePath("../../etc", blob))
        assertNull(PeerFileRoute.relativePath("", blob))
        assertNull(PeerFileRoute.relativePath("aaaa1111", ".."))
        assertNull(PeerFileRoute.relativePath("a/b", blob))
    }

    @Test
    fun `назва розмови теж приходить іззовні`() {
        assertTrue(PeerFileRoute.isConversationId("aaaa1111"))
        assertTrue(PeerFileRoute.isConversationId("a-b_c"))
        assertFalse(PeerFileRoute.isConversationId(".."))
        assertFalse(PeerFileRoute.isConversationId("a/b"))
        assertFalse(PeerFileRoute.isConversationId("a.b"))
        assertFalse(PeerFileRoute.isConversationId(""))
        assertFalse(PeerFileRoute.isConversationId("x".repeat(200)))
    }
}
