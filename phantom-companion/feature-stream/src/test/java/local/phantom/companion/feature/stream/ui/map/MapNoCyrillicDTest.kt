package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapNoCyrillicDTest {

    private val batchFiles = listOf(
        "src/main/java/local/phantom/companion/feature/stream/ui/map/dossier/PlaceDossier.kt",
        "src/main/java/local/phantom/companion/feature/stream/ui/map/dossier/DossierObjectCards.kt",
    )

    private val cyrillic = Regex("[\u0400-\u04FF]")

    @Test
    fun `batch D files contain no Cyrillic in string literals`() {
        val root = findModuleRoot()
        val offenders = mutableListOf<String>()
        for (rel in batchFiles) {
            val file = File(root, rel)
            assertTrue("Missing file: $rel", file.isFile)
            val stripped = stripComments(file.readText())
            for ((lineNo, literal) in extractStringLiterals(stripped)) {
                if (cyrillic.containsMatchIn(literal)) {
                    offenders.add("${file.name}:$lineNo $literal")
                }
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    private fun findModuleRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            val sub = File(dir, "phantom-companion/feature-stream")
            if (File(sub, "src/main/java").isDirectory) return sub
            dir = dir.parentFile
        }
        error("Cannot locate module root holding src/main/java")
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            if (i + 2 < n && src[i] == '"' && src[i + 1] == '"' && src[i + 2] == '"') {
                out.append("\"\"\"")
                i += 3
                while (i < n) {
                    if (i + 2 < n && src[i] == '"' && src[i + 1] == '"' && src[i + 2] == '"') {
                        out.append("\"\"\"")
                        i += 3
                        break
                    }
                    out.append(src[i])
                    i++
                }
            } else if (src[i] == '"') {
                out.append('"')
                i++
                while (i < n && src[i] != '"' && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < n) {
                        out.append(src[i]).append(src[i + 1])
                        i += 2
                    } else {
                        out.append(src[i])
                        i++
                    }
                }
                if (i < n) {
                    out.append(src[i])
                    i++
                }
            } else if (src[i] == '\'') {
                out.append('\'')
                i++
                while (i < n && src[i] != '\'' && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < n) {
                        out.append(src[i]).append(src[i + 1])
                        i += 2
                    } else {
                        out.append(src[i])
                        i++
                    }
                }
                if (i < n) {
                    out.append(src[i])
                    i++
                }
            } else if (i + 1 < n && src[i] == '/' && src[i + 1] == '/') {
                while (i < n && src[i] != '\n') i++
            } else if (i + 1 < n && src[i] == '/' && src[i + 1] == '*') {
                i += 2
                while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) {
                    if (src[i] == '\n') out.append('\n')
                    i++
                }
                i += 2
            } else {
                out.append(src[i])
                i++
            }
        }
        return out.toString()
    }

    private fun extractStringLiterals(src: String): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        var i = 0
        var line = 1
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c == '\n') {
                line++
                i++
            } else if (i + 2 < n && c == '"' && src[i + 1] == '"' && src[i + 2] == '"') {
                val startLine = line
                val sb = StringBuilder()
                i += 3
                while (i < n) {
                    if (i + 2 < n && src[i] == '"' && src[i + 1] == '"' && src[i + 2] == '"') {
                        i += 3
                        break
                    }
                    if (src[i] == '\n') line++
                    sb.append(src[i])
                    i++
                }
                result.add(startLine to sb.toString())
            } else if (c == '"') {
                val startLine = line
                val sb = StringBuilder()
                i++
                while (i < n && src[i] != '"' && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < n) {
                        sb.append(src[i]).append(src[i + 1])
                        i += 2
                    } else {
                        sb.append(src[i])
                        i++
                    }
                }
                if (i < n && src[i] == '"') i++
                result.add(startLine to sb.toString())
            } else if (c == '\'') {
                i++
                while (i < n && src[i] != '\'' && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < n) i += 2 else i++
                }
                if (i < n && src[i] == '\'') i++
            } else {
                i++
            }
        }
        return result
    }
}
