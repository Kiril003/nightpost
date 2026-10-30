package local.phantom.companion.ui.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WebPickedFilesTest {

    @Test
    fun `імʼя без шляху, службових символів і крапки на початку`() {
        assertEquals("фото.jpg", WebPickedFiles.safeName("../../фото.jpg"))
        assertEquals("a.pdf", WebPickedFiles.safeName("C:\\dir\\a.pdf"))
        assertEquals("hidden", WebPickedFiles.safeName(".hidden"))
        assertEquals("ab.png", WebPickedFiles.safeName("a<>b.png"))
        assertEquals("file", WebPickedFiles.safeName(null))
        assertEquals(120, WebPickedFiles.safeName("x".repeat(300)).length)
    }

    @Test
    fun `стара копія прибирається, свіжа лишається`() {
        val root = Files.createTempDirectory("picked").toFile()
        val old = File(root, "old").apply { mkdirs(); setLastModified(0L) }
        val fresh = File(root, "fresh").apply { mkdirs() }
        WebPickedFiles.sweep(root, nowMs = System.currentTimeMillis())
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `вибір файла йде через копію, а не чужим URI`() {
        val host = File("src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt").readText()
        assertTrue(host.contains("WebPickedFiles.copyAll(context, uris)"))
        val paths = File("src/main/res/xml/file_paths.xml").readText()
        assertTrue(paths.contains("path=\"picked/\""))
    }
}
