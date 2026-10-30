package local.phantom.companion.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Пауза після зміни ключа діє і на відповідь зі шторки, а не лише на веб. */
class KeyHoldReachTest {

    private fun src(path: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        val f = File(dir ?: fail("корінь не знайдено") as Nothing, "app/src/main/java/local/phantom/companion/$path")
        assertTrue("$path не знайдено", f.isFile)
        return f.readText()
    }

    @Test fun `відповідь зі шторки питає паузу ДО відправки і каже про неї словом`() {
        val r = src("service/PeerReplyReceiver.kt")
        val ask = r.indexOf("it.sendHeld")
        val send = r.indexOf("container.mesh.sendTyped(")
        assertTrue("шторка не питає паузу", ask > 0)
        assertTrue("пауза питається після відправки", ask < send)
        assertTrue(r.contains("PeerNotifications.noteReplyHeld(context, conversation)"))
    }

    @Test fun `«Продовжити» має двері в сховищі людей`() {
        assertTrue(src("data/PeerRosterStore.kt").contains("PeerRoster.continueAfterKeyChange(_peers.value, id)"))
    }
}
