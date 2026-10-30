package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.ui.messenger.WebMessengerSave.Shelf
import local.phantom.companion.ui.messenger.WebMessengerSave.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Зберегти» на телефоні було мертвою кнопкою: WebView без DownloadListener
 * не робить нічого з `<a download>`. Тут — усе, що вирішується без Android;
 * сам запис у MediaStore у JVM-тесті не перевірити (Robolectric у :app немає).
 */
class WebMessengerSaveTest {

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    private val jpeg = b64(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3))

    private fun refused(v: Verdict) = (v as Verdict.Refused).status to v.code

    @Test
    fun `фото — у Pictures, відео — у Movies, решта — у Download`() {
        assertEquals(Shelf.PICTURES, WebMessengerSave.shelfFor("image/jpeg"))
        assertEquals(Shelf.MOVIES, WebMessengerSave.shelfFor("video/mp4"))
        assertEquals(Shelf.DOWNLOADS, WebMessengerSave.shelfFor("application/pdf"))
        assertEquals(Shelf.DOWNLOADS, WebMessengerSave.shelfFor("audio/ogg"))
        assertEquals("Pictures/Nightpost", Shelf.PICTURES.where)
        assertEquals("Movies/Nightpost", Shelf.MOVIES.where)
        assertEquals("Download/Nightpost", Shelf.DOWNLOADS.where)
    }

    @Test
    fun `імʼя не виходить за теку й не ховається`() {
        assertEquals("passwd.txt", WebMessengerSave.cleanName("../../etc/passwd.txt", "text/plain"))
        assertEquals("evil.jpg", WebMessengerSave.cleanName("C:\\Windows\\evil.jpg", "image/jpeg"))
        assertEquals("hidden.png", WebMessengerSave.cleanName("...hidden.png", "image/png"))
        assertEquals("a b.pdf", WebMessengerSave.cleanName("a<>:\"|?*\u0000 b.pdf", "application/pdf"))
    }

    @Test
    fun `розширення з імені лишається, а без нього береться з роду`() {
        assertEquals("фото.jpg", WebMessengerSave.cleanName("фото", "image/jpeg"))
        assertEquals("clip.MP4".substringBeforeLast('.') + ".mp4", WebMessengerSave.cleanName("clip.MP4", "video/mp4"))
        assertEquals("Nightpost.webp", WebMessengerSave.cleanName("", "image/webp"))
        assertEquals("Nightpost", WebMessengerSave.cleanName("", "application/x-unknown"))
        val long = WebMessengerSave.cleanName("я".repeat(500) + ".pdf", "application/pdf")
        assertTrue(long.endsWith(".pdf"))
        assertTrue(long.length <= WebMessengerSave.MAX_NAME_CHARS)
    }

    @Test
    fun `справжнє фото проходить з полицею й іменем`() {
        val v = WebMessengerSave.judge("знімок.jpg", "image/jpeg", jpeg) as Verdict.Ready
        assertEquals(Shelf.PICTURES, v.shelf)
        assertEquals("знімок.jpg", v.name)
        assertEquals(6, v.bytes.size)
    }

    @Test
    fun `програми не зберігаються`() {
        assertEquals(415 to "bad_mime", refused(WebMessengerSave.judge("x.apk", "application/vnd.android.package-archive", jpeg)))
        assertEquals(415 to "bad_mime", refused(WebMessengerSave.judge("x.apk", "application/octet-stream", jpeg)))
        assertEquals(415 to "bad_mime", refused(WebMessengerSave.judge("setup.exe", "application/octet-stream", jpeg)))
    }

    @Test
    fun `фото з назвою не-фото відмовляється`() {
        assertEquals(415 to "bad_mime", refused(WebMessengerSave.judge("doc.pdf", "image/jpeg", jpeg)))
        assertEquals(415 to "bad_mime", refused(WebMessengerSave.judge("x.jpg", "not a mime", jpeg)))
    }

    @Test
    fun `порожнє й непрочитане — bad_body`() {
        assertEquals(400 to "bad_body", refused(WebMessengerSave.judge("a.jpg", "image/jpeg", "")))
        assertEquals(400 to "bad_body", refused(WebMessengerSave.judge("a.jpg", "image/jpeg", "@@@не base64@@@")))
    }

    @Test
    fun `стеля — та сама, що в меші`() {
        val over = "A".repeat(((PeerFile.MAX_BYTES + 4L) / 3 * 4 + 8).toInt())
        assertEquals(413 to "too_big", refused(WebMessengerSave.judge("big.bin", "application/octet-stream", over)))
    }

    @Test
    fun `маршрут видно дзеркалу мосту одним рядком`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val bridge = File(dir!!, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        val line = bridge.lines().single { it.contains("seg[1] == \"save\"") }
        assertTrue(line.contains("seg[1] == \"save\" && method == \"POST\" -> saveToPhone(body)"))
        assertTrue(line.substringBefore("->").contains("||").not())
        val route = bridge.substringAfter("private fun saveToPhone(").substringBefore("\n    private fun ")
        assertTrue(route.contains("WebMessengerSave.judge("))
        assertTrue(route.contains("WebMessengerSave.write(container.applicationContext.contentResolver"))
    }
}
