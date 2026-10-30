package local.phantom.companion.data.intent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Польова збірка: SMS-смуга Намірів є, і приймач відкритий лише системі. */
class IntentSmsFlavorTest {

    private fun field(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app/src/field/AndroidManifest.xml").isFile) dir = dir.parentFile
        return File(dir ?: error("немає польового маніфесту"), "app/src/field/AndroidManifest.xml").readText()
    }

    @Test fun `field вміє SMS`() {
        assertTrue(IntentSmsFlavor.SMS)
    }

    @Test fun `польовий маніфест просить обидва дозволи і ховає приймач за BROADCAST_SMS`() {
        val text = field()
        assertTrue(text.contains("android.permission.SEND_SMS"))
        assertTrue(text.contains("android.permission.RECEIVE_SMS"))
        val receiver = Regex("""<receiver[^>]*IntentSmsReceiver[^>]*>""").find(text)?.value.orEmpty()
        assertTrue("приймач SMS без замка системи", receiver.contains("android.permission.BROADCAST_SMS"))
    }
}
