package local.phantom.companion.navigation

import java.io.File
import local.phantom.companion.core.data.locale.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перелік мов першого запуску не обіцяє мови, якої немає.
 *
 * Мова в переліку = мова в `locales_config.xml` (те саме бачить система в
 * «Мові застосунку») = тека рядків у кожному модулі з рядками першого
 * запуску. Англійська — мова джерела (`values/`), решта — `values-<tag>/`.
 * Додати прапор без перекладу — червоне тут.
 */
class OfferedLanguagesTranslatedTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private val sourceLanguage = "en"

    @Test
    fun `offered languages are exactly the ones the system is told about`() {
        val config = File(root, "app/src/main/res/xml/locales_config.xml").readText()
        val declared = Regex("""android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toSet()
        assertEquals(declared, AppLanguage.offered.map { it.tag }.toSet())
    }

    /**
     * Огляд 08.10: «є файл strings*.xml» доводило лише існування файлу —
     * мова з одним перекладеним рядком проходила. Тепер у КОЖНОМУ модулі, де
     * є рядки, набір ключів `values-<tag>/` дорівнює набору `values/` (без
     * `translatable="false"`). Виняток — лише названий у [untranslatedOnPurpose].
     */
    @Test
    fun `every offered language translates every string of every module`() {
        val modules = root.listFiles().orEmpty()
            .filter { File(it, "src/main/res/values").isDirectory }
            .filter { keys(File(it, "src/main/res/values")).isNotEmpty() }
        assertTrue("модулів із рядками не знайшлося", modules.any { it.name == "app" } && modules.any { it.name == "feature-onboarding" })
        val gaps = mutableListOf<String>()
        for (module in modules) {
            val source = keys(File(module, "src/main/res/values"))
            for (l in AppLanguage.offered.filter { it.tag != sourceLanguage }) {
                val dir = File(module, "src/main/res/values-${l.tag}")
                val translated = keys(dir)
                val allowed = untranslatedOnPurpose["${module.name}/${l.tag}"].orEmpty()
                val missing = source - translated - allowed
                if (missing.isNotEmpty()) {
                    gaps += "${module.name}: «${l.nativeName}» без ${missing.size} із ${source.size} рядків, напр. ${missing.sorted().take(5)}"
                }
                val stale = translated - keys(File(module, "src/main/res/values"), withUntranslatable = true)
                if (stale.isNotEmpty()) gaps += "${module.name}/values-${l.tag}: ключі без джерела ${stale.sorted().take(5)}"
            }
        }
        assertTrue("мова в переліку, а перекладу немає:\n" + gaps.joinToString("\n"), gaps.isEmpty())
    }


    /** «модуль/мова» → ключі, які свідомо не перекладено. */
    private val untranslatedOnPurpose: Map<String, Set<String>> = emptyMap()

    private val resourceName = Regex("""<(string|plurals|string-array)\s+name="([^"]+)"([^>]*)>""")

    private fun keys(dir: File, withUntranslatable: Boolean = false): Set<String> =
        dir.listFiles { f -> f.extension == "xml" }.orEmpty()
            .flatMap { f -> resourceName.findAll(f.readText()).toList() }
            .filter { withUntranslatable || "translatable=\"false\"" !in it.groupValues[3] }
            .map { it.groupValues[2] }
            .toSet()

    @Test
    fun `the first-run strings exist in every offered language`() {
        val keys = listOf("onb_lang_line", "onb_lang_title", "onb_lang_continue")
        for (l in AppLanguage.offered) {
            val dir = if (l.tag == sourceLanguage) "values" else "values-${l.tag}"
            val xml = File(root, "feature-onboarding/src/main/res/$dir/strings.xml").readText()
            for (k in keys) assertTrue("$dir: немає $k", "name=\"$k\"" in xml)
        }
    }
}
