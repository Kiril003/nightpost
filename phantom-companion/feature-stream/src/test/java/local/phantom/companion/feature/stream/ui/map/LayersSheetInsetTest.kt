package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FR-8 (ніч 09.10, A52 кадр a52-15): аркуш «Шари й офлайн» відкривався
 * наполовину, і нижній ряд «Хто мене бачить · ніхто» лежав під кнопками
 * навігації. Відступ під смугою навігації стоїть у кінці прокрутки — у
 * пів-стані до нього не дійти. Модуль без compose ui-test, тож сторож читає
 * джерело аркуша.
 */
class LayersSheetInsetTest {

    private val src = File("src/main/java/local/phantom/companion/feature/stream/ui/map/MapHomeControls.kt")
        .takeIf { it.isFile } ?: File("feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapHomeControls.kt")

    private fun layersSheet(): String {
        val text = src.readText()
        val start = text.indexOf("internal fun MapLayersSheet(")
        assertTrue("MapLayersSheet зник із ${src.path}", start >= 0)
        return text.substring(start, text.indexOf("\n}\n", start))
    }

    @Test
    fun `layers sheet opens full, never half-way under the navigation bar`() {
        val body = layersSheet()
        assertTrue(
            "аркуш шарів має відкриватись повним (skipPartiallyExpanded = true)",
            Regex("""rememberModalBottomSheetState\(\s*skipPartiallyExpanded\s*=\s*true\s*\)""").containsMatchIn(body),
        )
    }

    @Test
    fun `the last row keeps clear of the navigation bar`() {
        assertTrue("вміст аркуша шарів має відступ під смугою навігації", layersSheet().contains(".navigationBarsPadding()"))
    }
}
