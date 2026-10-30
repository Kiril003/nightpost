package local.phantom.companion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Перший запуск на A52 (02.10): «Імʼя» тричі з друкарським апострофом, тоді як
 * месенджер і Профіль пишуть «імʼя» (U+02BC). Перший екран — не місце для двох
 * правописів одного слова.
 */
class OnboardingApostropheTest {
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("корінь дерева не знайдено")
    }

    @Test
    fun `екран імені першого запуску пише імʼя через ʼ`() {
        val xml = File(repoRoot(), "feature-onboarding/src/main/res/values-uk/strings.xml").readText()
        val nameStrings = Regex("""<string name="onb_name_[^"]*">([^<]*)</string>""").findAll(xml).map { it.groupValues[1] }.toList()
        val straight = nameStrings.filter { it.contains("\\'") || it.contains("'") || it.contains("’") }
        assertEquals("друкарський апостроф у рядках екрана імені: $straight", emptyList<String>(), straight)
    }
}
