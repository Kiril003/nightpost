package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MapNoCyrillicFTest {

    private val batchFiles = listOf(
        "src/main/java/local/phantom/companion/feature/stream/ui/map/prefetch/CorridorPrefetcher.kt",
        "src/main/java/local/phantom/companion/feature/stream/ui/map/MapViewModel.kt",
        "src/main/java/local/phantom/companion/feature/stream/ui/map/MapToolsCapsule.kt",
        "src/main/java/local/phantom/companion/feature/stream/ui/map/MapPoiSearch.kt",
        "src/main/java/local/phantom/companion/feature/stream/ui/map/MapRouteCoordinator.kt",
        "src/main/java/local/phantom/companion/feature/stream/ui/map/MapCanvasWiring.kt",
    )

    @Test
    fun noCyrillicLiteralsInBatchF() {
        val root = findModuleRoot()
        val violations = mutableListOf<String>()
        for (rel in batchFiles) {
            val file = File(root, rel)
            assertTrue("Missing file: ${file.absolutePath}", file.isFile)
            val stripped = stripComments(file.readText())
            stripped.lines().forEachIndexed { idx, line ->
                for (lit in extractStringLiterals(line)) {
                    if (lit.any { it in '\u0400'..'\u04FF' }) {
                        violations += "${file.name}:${idx + 1} \"$lit\""
                    }
                }
            }
        }
        assertTrue(
            "Cyrillic literals found in batch F:\n" + violations.joinToString("\n"),
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
        error("Could not locate feature-stream module root")
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inStr = false
        var triple = false
        while (i < src.length) {
            val c = src[i]
            if (!inStr && i + 1 < src.length && c == '/' && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') {
                    out.append(' ')
                    i++
                }
                continue
            }
            if (!inStr && i + 1 < src.length && c == '/' && src[i + 1] == '*') {
                out.append("  ")
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) {
                    out.append(if (src[i] == '\n') '\n' else ' ')
                    i++
                }
                if (i + 1 < src.length) {
                    out.append("  ")
                    i += 2
                }
                continue
            }
            if (c == '"') {
                if (!inStr && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"') {
                    inStr = true
                    triple = true
                    out.append("\"\"\"")
                    i += 3
                    continue
                } else if (inStr && triple && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"') {
                    inStr = false
                    triple = false
                    out.append("\"\"\"")
                    i += 3
                    continue
                } else if (!triple) {
                    var bs = 0
                    var k = i - 1
                    while (k >= 0 && src[k] == '\\') { bs++; k-- }
                    if (bs % 2 == 0) inStr = !inStr
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun extractStringLiterals(line: String): List<String> {
        val res = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            if (line[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < line.length && line[i] != '"') {
                    if (line[i] == '\\' && i + 1 < line.length) {
                        sb.append(line[i])
                        sb.append(line[i + 1])
                        i += 2
                    } else {
                        sb.append(line[i])
                        i++
                    }
                }
                res += sb.toString()
            }
            i++
        }
        return res
    }
}
