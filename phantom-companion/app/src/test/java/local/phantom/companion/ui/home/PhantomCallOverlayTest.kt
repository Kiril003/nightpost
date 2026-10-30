package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Дзвінок PHANTOM лежить ПОВЕРХ домівки, а не окремим екраном навігації.
 *
 * Окремий екран виймав домівку з композиції — а з нею й WebView месенджера
 * ([local.phantom.companion.ui.messenger.WebMessengerHost] нищить його в
 * `onDispose`). «Назад» з дзвінка будив месенджер наново: 1–2 с темного
 * «Відкриваю месенджер…» і список замість розмови з PHANTOM (A52, 02.10).
 *
 * GATE LAW: читає файли як текст — Compose у юніт-тесті `:app` не підняти.
 */
class PhantomCallOverlayTest {

    private fun source(rel: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val f = File(dir ?: fail("корінь дерева не знайдено") as Nothing, rel)
        if (!f.isFile) fail("немає файла $rel")
        return f.readText().lineSequence()
            .filterNot { it.trimStart().let { t -> t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") } }
            .joinToString("\n")
    }

    private val ui = "app/src/main/java/local/phantom/companion/ui"

    @Test
    fun `кнопка дзвінка в месенджері відкриває накладку, а не маршрут`() {
        val bridge = source("$ui/messenger/WebMessengerBridge.kt")
        val branch = bridge.substringAfter("WebNav.Act.CallPhantom ->").substringBefore("\n")
        assertTrue("міст не відкриває накладку дзвінка", "phantomCallOpen.value = true" in branch)
        assertFalse("міст досі веде на окремий екран дзвінка", "PhantomCall.path" in branch)
        // Запит вкладки йде через `home()` графа й перебудовує домівку разом із
        // WebView — розмова під дзвінком мінялась на список.
        assertFalse("дзвінок смикає запит вкладки — WebView народиться наново", "homeTabRequest" in branch)
    }

    @Test
    fun `накладку малює домівка, де живе WebView`() {
        val home = source("$ui/home/HomeScreen.kt")
        assertTrue("домівка не малює дзвінок", "PhantomCallScreen(" in home)
        assertTrue("дзвінок не закривається прапорцем", "phantomCallOpen.value = false" in home)
        // Поки дзвінок поверх, «Назад» належить йому, а не сторінці під ним.
        assertTrue("WebView перехоплює «Назад» з-під дзвінка", "takesBack = !callOpen" in home)
        val host = source("$ui/messenger/WebMessengerHost.kt")
        assertTrue("BackHandler сторінки не зважає на накладку", "enabled = active && takesBack" in host)
    }

    /**
     * Розмова, відкрита тапом по сповіщенню, і BACK: Chromium пропускає запис
     * історії без жесту, `canGoBack()` казав false — і BACK викидав із
     * застосунку (A52, 02.10). Хост спершу питає сторінку.
     */
    @Test
    fun `BACK спершу питає сторінку, а не лише історію WebView`() {
        val host = source("$ui/messenger/WebMessengerHost.kt")
        val back = host.substringAfter("val goBack").substringBefore("BackHandler(")
        assertTrue("BACK не питає сторінку", "WEB_BACK_JS" in back)
        val ask = back.indexOf("evaluateJavascript")
        val history = back.indexOf("canGoBack()")
        assertTrue("canGoBack вирішує раніше за сторінку", ask in 0 until history)
        assertTrue("__phantomBack" in host.substringAfter("WEB_BACK_JS ="))
    }
}
