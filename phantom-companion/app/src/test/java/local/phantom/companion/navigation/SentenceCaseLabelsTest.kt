package local.phantom.companion.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G4: every title, label, row, button, hint and chip starts with a capital in
 * every language (sentence case), so «how long history is kept» never reaches a
 * screen. Fragments that continue a sentence use other name endings.
 */
class SentenceCaseLabelsTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private val entry = Regex("""<string\s+name="([^"]+)"[^>]*>([^<]*)</string>""")
    private val labelName = Regex(
        "_(title|label|header|heading|section|tab|row|button|btn|action|chip|cta|name|cd|hint|subtitle|caption|empty|wipe|armed|detent_[a-z]+)$",
    )
    private val sample = Regex("^[a-z0-9]+_\\S*$")
    private val fragments = setOf("money_offline_empty")

    @Test
    fun `labels start with a capital in every language`() {
        val files = root.listFiles().orEmpty().flatMap { module ->
            File(module, "src/main/res").listFiles().orEmpty()
                .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
                .flatMap { dir -> dir.listFiles().orEmpty().filter { it.extension == "xml" } }
        }
        assertTrue("no resources found", files.any { it.path.endsWith("app/src/main/res/values/strings.xml") })
        val lower = files.flatMap { file ->
            entry.findAll(file.readText()).mapNotNull { m ->
                val (name, value) = m.destructured
                val first = value.trimStart().firstOrNull()
                if (labelName.containsMatchIn(name) && name !in fragments && first != null && first.isLetter() && first.isLowerCase() &&
                    !sample.matches(value.trim())
                ) {
                    "${file.parentFile.parentFile.parentFile.parentFile.parentFile.name}/${file.parentFile.name}/$name = $value"
                } else {
                    null
                }
            }.toList()
        }
        assertEquals(lower.joinToString("\n"), 0, lower.size)
    }
}
