package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapNoCyrillicHTest {

    private val batchFiles = listOf(
        "local/phantom/companion/feature/stream/ui/map/NominatimClient.kt",
        "local/phantom/companion/feature/stream/ui/map/EventMarks.kt",
        "local/phantom/companion/feature/stream/ui/map/EvacuationCoordinator.kt",
        "local/phantom/companion/feature/stream/ui/map/mount/MountGlanceScreen.kt",
        "local/phantom/companion/feature/stream/ui/map/OfflineRegionsManager.kt",
        "local/phantom/companion/feature/stream/ui/map/OfflineBasemapStyle.kt",
    )

    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    @Test
    fun batchFilesHaveNoCyrillicStringLiterals() {
        val root = findModuleRoot()
        val violations = mutableListOf<String>()
        for (rel in batchFiles) {
            val file = File(root, "src/main/java/$rel")
            assertTrue("Missing file: ${file.absolutePath}", file.isFile)
            val stripped = stripComments(file.readText())
            for ((lineNo, literal) in extractStringLiterals(stripped)) {
                if (cyrillic.containsMatchIn(literal)) {
                    violations += "${file.name}:$lineNo \"$literal\""
                }
            }
        }
        assertTrue(
            "Cyrillic string literals found:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun findModuleRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            val sub = File(dir, "phantom-companion/feature-stream")
            if (File(sub, "src/main/java").isDirectory) return sub
            dir = dir.parentFile
        }
        error("Cannot locate feature-stream module root from ${File("").absolutePath}")
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
                i += 3
                val sb = StringBuilder()
                while (i < n) {
                    if (i + 2 < n && src[i] == '"' && src[i + 1] == '"' && src[i + 2] == '"') {
                        i += 3
                        break
                    }
                    if (src[i] == '\n') line++
                    sb.append(src[i])
                    i++
                }
                result += startLine to sb.toString()
            } else if (c == '"') {
                val startLine = line
                i++
                val sb = StringBuilder()
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
                result += startLine to sb.toString()
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
