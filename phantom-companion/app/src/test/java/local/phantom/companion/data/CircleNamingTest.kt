package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircleNamingTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun values(locale: String): Map<String, String> {
        val file = File(root, "app/src/main/res/$locale/strings_circles.xml")
        assertTrue("${file.path} missing", file.isFile)
        return Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `circle strings never name a circle in en uk or cs`() {
        val forbidden = Regex("circle|коло|кол|kruh|okruh", RegexOption.IGNORE_CASE)
        val locales = listOf("values", "values-uk", "values-cs").associateWith(::values)
        val keys = locales.getValue("values").keys
        assertEquals(setOf("circle_personal", "circle_social"), keys)
        locales.forEach { (locale, map) ->
            assertEquals("$locale keys", keys, map.keys)
            map.forEach { (key, value) ->
                assertTrue("$locale/$key «$value» names a circle", !forbidden.containsMatchIn(value))
            }
        }
    }
}
