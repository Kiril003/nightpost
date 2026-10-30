package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * «Шари й офлайн» у нічній темі (A52, 02.10): біла смуга під кнопками навігації
 * і ручка розгорнутого аркуша під рядком стану.
 *
 * Material-аркуш живе у власному вікні й бере вигляд смуг з теми ТЕЛЕФОНА.
 * Читає джерело як текст: вікно діалогу в юніт-тесті не підняти.
 */
class LayersSheetInsetsTest {

    private fun source(rel: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val f = File(dir ?: fail("корінь дерева не знайдено") as Nothing, rel)
        if (!f.isFile) fail("немає файла $rel")
        return f.readText()
    }

    @Test
    fun `аркуш шарів бере смуги з теми PHANTOM і зупиняється під рядком стану`() {
        val sheet = source("feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapHomeControls.kt")
            .substringAfter("fun MapLayersSheet(").substringBefore("\n}\n")
        val call = sheet.substringAfter("ModalBottomSheet(").substringBefore(") {")
        assertTrue("розгорнутий аркуш знову лізе під рядок стану", "statusBarsPadding()" in call)
        val body = sheet.substringAfter(") {")
        assertTrue("смуги вікна аркуша знову за темою телефона", "SheetWindowBars()" in body)
    }

    @Test
    fun `смуги вікна аркуша — з теми PHANTOM і без білої підкладки`() {
        val bars = source("core-design/src/main/java/local/phantom/companion/core/design/component/SheetWindowBars.kt")
        assertTrue("LocalPhantomColors.current.isDark" in bars)
        assertTrue("isNavigationBarContrastEnforced = false" in bars)
        assertTrue("isAppearanceLightNavigationBars = !dark" in bars)
    }
}
