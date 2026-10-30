package local.phantom.companion.feature.stream.ui.map.here

import java.io.File
import local.phantom.companion.core.truth.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чеський телефон не читає українських вироків «тут зараз»: мова застосунку,
 * якої немає в `core-truth`, іде на англійське дзеркало, а не на українську.
 */
class HereNowLangTest {

    @Test
    fun `only Ukrainian reads the Ukrainian verdicts`() {
        assertEquals(Lang.UK, hereNowLang("uk"))
        assertEquals(Lang.EN, hereNowLang("en"))
        assertEquals(Lang.EN, hereNowLang("cs"))
    }

    @Test
    fun `every language folder names itself and the block reads that name`() {
        val res = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "feature-stream/src/main/res").isDirectory }
            .resolve("feature-stream/src/main/res")
        val tags = listOf("values-uk" to "uk", "values" to "en", "values-cs" to "cs")
        for ((dir, tag) in tags) {
            val xml = File(res, "$dir/strings_here_now.xml").readText()
            assertTrue("$dir: here_now_lang не $tag", """<string name="here_now_lang">$tag</string>""" in xml)
        }
        val block = File(res.parentFile, "java/local/phantom/companion/feature/stream/ui/map/here/HereNowBlock.kt").readText()
        assertTrue("блок не читає hereNowLang", "hereNowLang(stringResource(R.string.here_now_lang))" in block)
    }
}
