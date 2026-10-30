package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapNoCyrillicCTest {

    private val batchFiles = listOf(
        "src/main/java/local/phantom/companion/feature/stream/ui/map/dossier/WikiEntityResolution.kt",
        "src/main/java/local/phantom/companion/feature/stream/ui/map/dossier/DossierLoader.kt",
    )

    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    @Test
    fun batchCFilesHaveNoCyrillicLiterals() {
        val root = findModuleRoot()
        val offenders = mutableListOf<String>()
        for (rel in batchFiles) {
            val file = File(root, rel)
            assertTrue("Missing $rel", file.isFile)
            val stripped = stripComments(file.readText())
            for ((line, literal) in extractStringLiterals(stripped)) {
                if (cyrillic.containsMatchIn(literal)) {
                    offenders += "${file.name}:$line $literal"
                }
            }
        }
        assertTrue("Cyrillic literals found:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    private fun findModuleRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            val sub = File(dir, "phantom-companion/feature-stream")
            if (File(sub, "src/main/java").isDirectory) return sub
            val sub2 = File(dir, "feature-stream")
            if (File(sub2, "src/main/java").isDirectory) return sub2
            dir = dir.parentFile
        }
        error("Cannot locate feature-stream module root")
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                while (i < n && src[i] != '\n') i++
            } else if (c == '/' && i + 1 < n && src[i + 1] == '*') {
                i += 2
                while (i < n && !(src[i] == '*' && i + 1 < n && src[i + 1] == '/')) {
                    if (src[i] == '\n') out.append('\n')
                    i++
                }
                if (i < n) i += 2
            } else if (c == '"') {
                if (i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                    out.append("\"\"\"")
                    i += 3
                    while (i < n && !(src[i] == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"')) {
                        out.append(src[i])
                        i++
                    }
                    if (i < n) {
                        out.append("\"\"\"")
                        i += 3
                    }
                } else {
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
                    if (i < n && src[i] == '"') {
                        out.append('"')
                        i++
                    }
                }
            } else if (c == '\'') {
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
                if (i < n && src[i] == '\'') {
                    out.append('\'')
                    i++
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun extractStringLiterals(src: String): List<Pair<Int, String>> {
        val list = mutableListOf<Pair<Int, String>>()
        var i = 0
        var line = 1
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c == '\n') {
                line++
                i++
            } else if (c == '"') {
                val startLine = line
                val buf = StringBuilder()
                if (i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                    i += 3
                    while (i < n && !(src[i] == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"')) {
                        if (src[i] == '\n') line++
                        buf.append(src[i])
                        i++
                    }
                    if (i < n) i += 3
                } else {
                    i++
                    while (i < n && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < n) {
                            buf.append(src[i]).append(src[i + 1])
                            i += 2
                        } else {
                            buf.append(src[i])
                            i++
                        }
                    }
                    if (i < n && src[i] == '"') i++
                }
                list += startLine to "\"$buf\""
            } else if (c == '\'') {
                val startLine = line
                val buf = StringBuilder()
                i++
                while (i < n && src[i] != '\'' && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < n) {
                        buf.append(src[i]).append(src[i + 1])
                        i += 2
                    } else {
                        buf.append(src[i])
                        i++
                    }
                }
                if (i < n && src[i] == '\'') i++
                list += startLine to "'$buf'"
            } else {
                i++
            }
        }
        return list
    }
}
