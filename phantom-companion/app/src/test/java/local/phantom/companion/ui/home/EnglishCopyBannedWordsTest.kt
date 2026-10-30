package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishCopyBannedWordsTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `жоден англійський рядок на екрані не каже light, blackout, darkness, Bluetooth чи mesh`() {
        val banned = Regex("""\b(light|blackout|darkness|bluetooth|mesh)\b""", RegexOption.IGNORE_CASE)
        val hits = File(root, "app/src/main/res/values").listFiles { f -> f.extension == "xml" }!!
            .flatMap { f ->
                Regex("""<string name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(f.readText())
                    .map { it.groupValues[1] to it.groupValues[2] }.toList()
            }
            .filter { (key, value) -> !key.startsWith("settings_kw_") && banned.containsMatchIn(value) }
            .map { it.first }
        assertEquals("заборонені слова в англійських рядках: $hits", emptyList<String>(), hits)
    }
}
