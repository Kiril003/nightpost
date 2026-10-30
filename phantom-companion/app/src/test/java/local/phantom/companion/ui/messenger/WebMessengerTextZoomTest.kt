package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

/**
 * Месенджер має слухати повзунок ЗАСТОСУНКУ, а не системний множник.
 *
 * WebView за замовчуванням множить кожен текст на системний масштаб шрифту.
 * Виміряно 14.09 на 1080×2400, бандл `index-CIrwRVPt.js`: при
 * `settings put system font_scale 1.30` підпис виріс 15px → 19.5px, а
 * ширина екрана лишилась 412 CSS px. При «великому» розмірі екрана (щільність
 * 540 → 320 CSS px) текст у картках почало різати: назву розмови 186→109px,
 * рядок задачі 251→170px. Рідні екрани йдуть за `LocalPhantomFontScale`
 * (0.85..1.30), і веб мусить іти за тим самим числом — інакше половини одного
 * застосунку показують різний розмір, і жодну з них людина не обирала.
 *
 * GATE LAW: читає файл як текст, без коментарів.
 */
class WebMessengerTextZoomTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private val host by lazy {
        val f = File(
            repoRoot(),
            "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt",
        )
        if (!f.isFile) fail("немає WebMessengerHost.kt")
        f.readText().lineSequence()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    @Test
    fun `the web view pins text zoom to the app font scale`() {
        assertTrue(
            "textZoom не виставлено — веб поїде за системним множником, якого застосунок не питав",
            host.contains("textZoom ="),
        )
        assertTrue(
            "textZoom має рахуватись із LocalPhantomFontScale, а не з константи",
            host.contains("fontScale * 100"),
        )
    }

    /**
     * WebView створюється в `remember` один раз: без окремого ефекту повзунок
     * у налаштуваннях мовчки не діяв би до перезапуску застосунку.
     */
    @Test
    fun `changing the slider reaches a web view that already exists`() {
        assertTrue(
            "немає LaunchedEffect на fontScale — зміна розміру не дійде до відкритого месенджера",
            host.contains("LaunchedEffect(webView, fontScale)"),
        )
    }
}
