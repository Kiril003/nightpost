package local.phantom.companion.ui.messenger

import java.io.File
import java.nio.file.Files
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.data.PeerFileStore
import local.phantom.companion.data.PeerLogSlot
import local.phantom.companion.data.PeerOutboxStore
import local.phantom.companion.data.SavedNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebMessengerSavedTest {

    private class Slot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    /** Єдиний порт до сітки, який клас узагалі має. */
    private class MeshPort(private val stray: List<PeerMessage> = emptyList()) : WebMessengerSaved.Journal {
        var reads = 0
        val forgotten = mutableListOf<PeerMessage>()
        override fun lines(): List<PeerMessage> {
            reads++
            return stray
        }
        override fun forget(lines: List<PeerMessage>) {
            forgotten += lines
        }
    }

    private var clock = 1_000L

    private fun handler(slot: Slot, port: MeshPort = MeshPort()) = WebMessengerSaved(
        notes = SavedNotes(PeerOutboxStore(slot, bounded = false)),
        me = { "me_node" },
        journal = port,
        placeFrame = { null },
        isJson = { it.trim().startsWith("{") },
        now = { clock++ },
    )

    private val base = listOf("messenger", "conversations", SavedNotes.ID)

    private fun WebMessengerSaved.post(text: String, kind: String = "text", clientId: String = "") =
        handle("POST", base + "messages", 0, WebMessengerSaved.Input(kind, text, clientId), null, "Збережене")

    private fun WebMessengerSaved.feedIds() =
        (handle("GET", base + "messages", 0, null, null, "Збережене") as WebMessengerSaved.Reply.Feed)
            .lines.map { it.second.id }

    @Test
    fun `надсилання в Збережене не торкається сітки`() {
        val port = MeshPort()
        val saved = handler(Slot(), port)

        val sent = saved.post("перевірка 23.09") as WebMessengerSaved.Reply.Line
        saved.post("друга")
        saved.handle("PATCH", base + listOf("messages", sent.note.id), 0,
            WebMessengerSaved.Input("text", "виправлено"), null, "Збережене")
        saved.handle("DELETE", base + listOf("messages", sent.note.id), 0, null, null, "Збережене")
        saved.feedIds()

        assertEquals(PeerMessage.State.DELIVERED, sent.note.state)
        assertEquals("me_node", sent.note.fromId)
        assertEquals(1, port.reads)
        assertTrue(port.forgotten.isEmpty())
    }

    @Test
    fun `нотатки переживають перезапуск і не перезаписуються новою`() {
        val slot = Slot()
        handler(slot).apply {
            post("перша", clientId = "c1")
            post("друга", clientId = "c2")
            post("третя", clientId = "c3")
        }

        val restarted = handler(slot)
        val fourth = restarted.post("четверта", clientId = "c4") as WebMessengerSaved.Reply.Line

        assertEquals(listOf("c1", "c2", "c3", "c4"), restarted.feedIds())
        assertEquals(4, fourth.seq)
        val bodies = (restarted.handle("GET", base + "messages", 0, null, null, "") as WebMessengerSaved.Reply.Feed)
            .lines.map { it.second.body }
        assertEquals(listOf("перша", "друга", "третя", "четверта"), bodies)
    }

    @Test
    fun `повтор із тим самим client_id не подвоює нотатку`() {
        val saved = handler(Slot())
        saved.post("раз", clientId = "c1")
        saved.post("раз", clientId = "c1")

        assertEquals(listOf("c1"), saved.feedIds())
    }

    @Test
    fun `лист, що лежав у журналі сітки на адресу нотаток, переноситься і не губиться`() {
        val stray = PeerMessage("old1", "me_node", SavedNotes.ID, "стара нотатка", 5L, state = PeerMessage.State.QUEUED)
        val other = PeerMessage("x", "me_node", "peer", "людині", 6L)
        val port = MeshPort(listOf(stray, other))
        val slot = Slot()

        handler(slot, port).post("нова")

        assertEquals(listOf(stray), port.forgotten)
        val reopened = handler(slot)
        assertEquals("old1", reopened.feedIds().first())
    }

    @Test
    fun `рядок у списку — справжня розмова, навіть порожня`() {
        val row = handler(Slot()).row("Збережене")

        assertEquals(SavedNotes.ID, row["id"])
        assertEquals("saved", row["kind"])
        assertEquals("saved", row["circle"])
        assertEquals(true, row["pinned"])
        assertNull(row["contact_id"])
        assertNull(row["last_at"])
        assertEquals(0, row["unread_count"])
    }

    @Test
    fun `видалити саму розмову не можна, лише очистити`() {
        val saved = handler(Slot())
        saved.post("лишаюсь")

        val r = saved.handle("DELETE", base, 0, null, null, "")

        assertTrue(r is WebMessengerSaved.Reply.Refused)
        assertEquals(1, saved.feedIds().size)
    }

    @Test
    fun `імпорт із веба ідемпотентний і стає за часом`() {
        val saved = handler(Slot())
        saved.post("зараз", clientId = "now")
        val batch = listOf(
            WebMessengerSaved.Input("text", "вчора", "idb1", sentAtMs = 10L),
            WebMessengerSaved.Input("text", "позавчора", "idb0", sentAtMs = 5L),
        )

        val first = saved.handle("POST", base + "import", 0, null, batch, "") as WebMessengerSaved.Reply.Done
        val second = saved.handle("POST", base + "import", 0, null, batch, "") as WebMessengerSaved.Reply.Done

        assertEquals(2, first.fields["imported"])
        assertEquals(0, second.fields["imported"])
        assertEquals(listOf("idb0", "idb1", "now"), saved.feedIds())
    }

    @Test
    fun `міст віддає шлях нотаток до будь-якої гілки, що знає сітку`() {
        assertTrue(WebMessengerSaved.claims(base + "messages"))
        assertTrue(WebMessengerSaved.claims(base))
        assertFalse(WebMessengerSaved.claims(listOf("messenger", "conversations", SavedNotes.ID + "_x")))

        val route = bridgeSource().substringAfter("fun route(method: String").substringBefore("\n    private ")
        val claim = route.indexOf("WebMessengerSaved.claims(seg)")
        val firstMesh = listOf("WebMessengerGroups.find(", "mesh.outbox", "return when")
            .map { route.indexOf(it) }.filter { it >= 0 }.min()
        assertTrue("нотатки перехоплюються не першими", claim in 0 until firstMesh)
    }

    /** Опис вкладення таким, яким його кладе веб після шифрування й запису байтів. */
    private fun mediaBody(name: String, mime: String) =
        """{"name":"$name","size":4812,"mime":"$mime","sha256":"${"ab".repeat(32)}",""" +
            """"blob_id":"${"0f".repeat(16)}","key_hex":"${"11".repeat(32)}","nonce_hex":"${"22".repeat(12)}","duration":3}"""

    /**
     * Власник записав собі голосове — і бачив «рід voice у Збереженому поки не
     * зберігається» (A52, 02.10). Голос і кружок — той самий опис вкладення,
     * що й фото; без цього тесту вони знову спинялись би на заслоні родів.
     */
    @Test
    fun `голосове й кружок лягають у Збережене і переживають перезапуск`() {
        val slot = Slot()
        handler(slot).apply {
            val voice = post(mediaBody("voice_1790.webm", "audio/webm"), kind = "voice", clientId = "v1")
            assertTrue("голосове відмовлено: $voice", voice is WebMessengerSaved.Reply.Line)
            val circle = post(mediaBody("video_note_1790.webm", "video/webm"), kind = "video-note", clientId = "k1")
            assertTrue("кружок відмовлено: $circle", circle is WebMessengerSaved.Reply.Line)
        }
        val again = handler(slot)
        assertEquals(listOf("v1", "k1"), again.feedIds())
        val row = (again.handle("GET", base, 0, null, null, "Збережене") as WebMessengerSaved.Reply.Row).fields
        // Останнім ліг кружок — і рядок каже «кружок», а не «файл» (до 02.10
        // рід вкладення вгадувався лише як фото чи файл).
        assertEquals(WebMessengerKinds.VIDEO_NOTE, row["last_kind"])
    }

    /** Негативний контроль: рід голосу не робить вантажем будь-яке слово. */
    @Test
    fun `голосове без опису вкладення не записується`() {
        val saved = handler(Slot())
        val refused = saved.post("просто слова", kind = "voice")
        assertTrue(refused is WebMessengerSaved.Reply.Refused)
        assertEquals("bad_media", (refused as WebMessengerSaved.Reply.Refused).code)
        assertTrue(saved.feedIds().isEmpty())
        // Невідомий рід і далі спиняється тим самим словом, що й раніше.
        val unknown = saved.post("щось", kind = "geo:live") as WebMessengerSaved.Reply.Refused
        assertEquals("kind_not_carried", unknown.code)
    }

    @Test
    fun `вкладення, збережене у Збережене, переживає очищення вихідної розмови`() {
        val dir = Files.createTempDirectory("saved_blob_test").toFile()
        try {
            val files = PeerFileStore(dir) { null }
            val blobId = files.put("p1", byteArrayOf(1, 2, 3, 4))!!
            val body = """{"name":"doc.bin","size":4,"mime":"application/octet-stream","sha256":"${"ab".repeat(32)}","blob_id":"$blobId","key_hex":"${"11".repeat(32)}","nonce_hex":"${"22".repeat(12)}"}"""
            val att = PeerFile.describe(body)!!
            assertTrue(files.copyTo(SavedNotes.ID, blobId))
            assertTrue(files.forget("p1"))
            assertEquals(PeerFileStore.Fate.READY, files.fate(SavedNotes.ID, att))
            assertEquals(4, files.bytes(blobId)?.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun bridgeSource(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь дерева не знайдено") as Nothing
        return File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
    }

    @Test
    fun `смайл на своїй нотатці ставиться і знімається, а стрічка не росте`() {
        val saved = handler(Slot())
        val note = (saved.post("купити хліб") as WebMessengerSaved.Reply.Line).note
        val react = { e: String ->
            saved.handle("POST", base + listOf("messages", note.id, "reactions"), 0,
                WebMessengerSaved.Input("", "", emoji = e), null, "Збережене")
        }

        val put = react("👍")
        assertTrue("реакція в Збереженому не відмовляє: $put", put is WebMessengerSaved.Reply.Line)
        assertEquals(listOf("me_node"), saved.reactions()[note.id]?.get("👍"))
        assertEquals(listOf(note.id), saved.feedIds())

        react("👍")
        assertTrue(saved.reactions()[note.id]?.get("👍").isNullOrEmpty())

        val stray = saved.handle("POST", base + listOf("messages", "nope", "reactions"), 0,
            WebMessengerSaved.Input("", "", emoji = "👍"), null, "Збережене")
        assertEquals(404, (stray as WebMessengerSaved.Reply.Refused).status)
    }
}
