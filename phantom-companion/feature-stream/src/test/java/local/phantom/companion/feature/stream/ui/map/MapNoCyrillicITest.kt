package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MapNoCyrillicITest {

    private val batchFiles = listOf(
        "local/phantom/companion/feature/stream/ui/map/MapSearchRows.kt",
        "local/phantom/companion/feature/stream/ui/map/MapChrome.kt",
        "local/phantom/companion/feature/stream/ui/map/MapLiveSession.kt",
        "local/phantom/companion/feature/stream/ui/map/MapComposeOverlays.kt",
    )

    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    private fun moduleRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            if (File(dir, "feature-stream/src/main/java").isDirectory) {
                return File(dir, "feature-stream")
            }
            if (File(dir, "phantom-companion/feature-stream/src/main/java").isDirectory) {
                return File(dir, "phantom-companion/feature-stream")
            }
            dir = dir.parentFile
        }
        fail("module root with src/main/java not found")
        throw IllegalStateException()
    }

    private fun findCyrillicStringLiterals(file: File): List<String> {
        val text = file.readText()
        val offenders = mutableListOf<String>()
        var i = 0
        var line = 1
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\n') {
                line++
                i++
                continue
            }
            if (c == '/' && i + 1 < n && text[i + 1] == '/') {
                i += 2
                while (i < n && text[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < n && depth > 0) {
                    if (text[i] == '\n') {
                        line++
                        i++
                    } else if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                        depth++
                        i += 2
                    } else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                        depth--
                        i += 2
                    } else {
                        i++
                    }
                }
                continue
            }
            if (c == '\'' ) {
                i++
                while (i < n && text[i] != '\'' && text[i] != '\n') {
                    if (text[i] == '\\' && i + 1 < n) i += 2 else i++
                }
                if (i < n && text[i] == '\'') i++
                continue
            }
            if (c == '"') {
                val startLine = line
                if (i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                    val start = i
                    i += 3
                    while (i < n && !(i + 2 < n && text[i] == '"' && text[i + 1] == '"' && text[i + 2] == '"')) {
                        if (text[i] == '\n') line++
                        i++
                    }
                    if (i + 2 < n) i += 3
                    val lit = text.substring(start, i)
                    if (cyrillic.containsMatchIn(lit)) {
                        offenders.add("${file.name}:$startLine $lit")
                    }
                } else {
                    val start = i
                    i++
                    while (i < n && text[i] != '"' && text[i] != '\n') {
                        if (text[i] == '\\' && i + 1 < n) i += 2 else i++
                    }
                    if (i < n && text[i] == '"') i++
                    val lit = text.substring(start, i)
                    if (cyrillic.containsMatchIn(lit)) {
                        offenders.add("${file.name}:$startLine $lit")
                    }
                }
                continue
            }
            i++
        }
        return offenders
    }

    @Test
    fun batchFilesHaveNoCyrillicLiterals() {
        val root = File(moduleRoot(), "src/main/java")
        val hits = batchFiles.flatMap { rel ->
            val f = File(root, rel)
            if (!f.isFile) fail("missing batch file: $rel")
            findCyrillicStringLiterals(f)
        }
        assertEquals(emptyList<String>(), hits)
    }
}
