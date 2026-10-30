package local.phantom.companion.core.design.component

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A52, r19: на «Приватності» й сторінці Nightpost рядки лягали під системні
 * кнопки. Відступ стояв усередині прокрутки, тож вікно прокрутки сягало під
 * навбар. Відступ має звужувати саме вікно — тоді жоден рядок туди не заходить.
 */
class SubScreenInsetTest {

    private val kit: String = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "core-design/src/main/java/local/phantom/companion/core/design/component/PhantomKit.kt") }
        .firstOrNull { it.isFile }
        ?.readText()
        ?: error("немає PhantomKit.kt")

    @Test
    fun `прокрутка SubScreen закінчується над навбаром`() {
        val start = kit.indexOf("fun SubScreen(")
        assertTrue("SubScreen зник", start >= 0)
        val body = kit.substring(start, kit.indexOf("\n}\n", start))
        val chain = body.substring(body.lastIndexOf("Modifier", body.indexOf(".verticalScroll(")))
        val inset = chain.indexOf(".navigationBarsPadding()")
        val scroll = chain.indexOf(".verticalScroll(")
        assertTrue("у прокрутки SubScreen немає відступу навбару", inset >= 0)
        assertTrue("відступ навбару всередині прокрутки — останній рядок під кнопками", inset < scroll)
    }
}
