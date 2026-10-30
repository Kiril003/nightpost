package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Голосове людям доходить до живого телефона: міст, рядок списку і вузька дорога. */
class VoicePeopleReachTest {

    private fun src(path: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        val f = File(dir ?: fail("корінь не знайдено") as Nothing, "app/src/main/java/local/phantom/companion/$path")
        assertTrue("$path не знайдено", f.isFile)
        return f.readText()
    }

    @Test fun `обидві дороги мосту відмовляють голосу без опису, а рядок списку не показує імені файла`() {
        val bridge = src("ui/messenger/WebMessengerBridge.kt")
        val guard = "if (WebMessengerKinds.mediaWithoutDescription(kind, text))"
        assertTrue("одна з двох доріг (група чи людина) без сторожа", bridge.split(guard).size - 1 == 2)
        assertTrue(bridge.contains("lastAtt.kind == PeerFile.VOICE || lastAtt.kind == PeerFile.VIDEO_NOTE"))
    }

    @Test fun `вузька дорога сітки бачить короткі голосові в усьому журналі`() {
        assertTrue(src("data/PeerMesh.kt").contains("PeerLane.speechBlobs(log)"))
    }
}
