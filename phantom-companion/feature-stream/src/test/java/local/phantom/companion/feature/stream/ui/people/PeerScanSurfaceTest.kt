package local.phantom.companion.feature.stream.ui.people

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вікно сканера коду, утримане у своїй формі читанням власного джерела.
 *
 * Перевірки переїхали сюди 04.10 із `PeerHandshakeSurfaceTest`: аркуш
 * знайомства (`PeerHandshakeSheet`) пішов із дерева разом із мертвим
 * `PeopleScreen`, а сканер лишився — його відкриває картка людини, і саме він
 * єдиний дає рівень довіри «камера». Три правила сканера — ті самі, слово в
 * слово: власне вікно (бо всередині аркуша, що тягнеться вниз, націлювання
 * камери викидало знайомство), «закрити» на повні 48dp і поверхня, що
 * малюється всередині діалогу.
 *
 * Це перевірки форми, а не пікселів: що камера справді малює — лише апарат.
 */
class PeerScanSurfaceTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return requireNotNull(dir) { "repo root with feature-stream/ not found" }
    }

    private fun source(name: String): String {
        val f = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people/$name",
        )
        assertTrue("missing $name", f.isFile)
        return f.readText()
    }

    private val scan by lazy { source("PeerScanSheet.kt") }

    @Test
    fun `the camera scans from its own window, not inside the drag-to-dismiss sheet`() {
        assertTrue(
            "PeerScanSheet must host the camera in a Dialog: inside PhantomSheet a " +
                "downward nudge to aim the camera throws the handshake away.",
            scan.contains("androidx.compose.ui.window.Dialog"),
        )
    }

    @Test
    fun `the way out is a full 48dp target`() {
        val floor = "heightIn(min = 48.dp)"
        assertTrue("scan «закрити» is under 48dp", scan.contains(floor))
    }

    @Test
    fun `the viewfinder uses the surface that composites inside a dialog`() {
        assertTrue(
            "PeerScanSheet must delegate the viewfinder to LiveQrCamera.",
            scan.contains("LiveQrCamera("),
        )
        val liveCamera = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/scan/LiveQrCamera.kt",
        )
        assertTrue("missing LiveQrCamera.kt", liveCamera.isFile)
        assertTrue(
            "PreviewView in a Dialog needs COMPATIBLE mode or it can draw black.",
            liveCamera.readText().contains("ImplementationMode.COMPATIBLE"),
        )
    }
}
