package local.phantom.companion.data.intent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Play-збірка: SMS-смуги Намірів немає ні в коді, ні в маніфесті. */
class IntentSmsFlavorTest {

    private fun app(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app/src/main/AndroidManifest.xml").isFile) dir = dir.parentFile
        return File(dir ?: error("не знайшов корінь дерева"), "app")
    }

    @Test fun `store не вміє SMS`() {
        assertFalse(IntentSmsFlavor.SMS)
    }

    @Test fun `ні головний, ні магазинний маніфест не просять SMS`() {
        for (path in listOf("src/main/AndroidManifest.xml", "src/store/AndroidManifest.xml")) {
            val text = File(app(), path).readText()
            assertFalse("$path просить SEND_SMS", text.contains("android.permission.SEND_SMS"))
            assertFalse("$path просить RECEIVE_SMS", text.contains("android.permission.RECEIVE_SMS"))
        }
        assertTrue(File(app(), "src/main/AndroidManifest.xml").isFile)
    }
}
