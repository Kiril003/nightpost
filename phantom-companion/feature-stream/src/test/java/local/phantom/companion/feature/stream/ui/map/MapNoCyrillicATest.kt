package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MapNoCyrillicATest {

    private val batchFiles = listOf(
        "src/main/java/local/phantom/companion/feature/stream/ui/map/MapDiscoveryCatalog.kt",
    )

    private fun moduleRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            val sub = File(dir, "phantom-companion/feature-stream")
            if (File(sub, "src/main/java").isDirectory) return sub
            val sub2 = File(dir, "feature-stream")
            if (File(sub2, "src/main/java").isDirectory) return sub2
            dir = dir.parentFile
        }
        error("Could not locate feature-stream module root")
    }

    @Test
    fun `batch A files contain no Cyrillic in string literals`() {
        val root = moduleRoot()
        val hits = mutableListOf<String>()
        val cyrillic = Regex("[\\u0400-\\u04FF]")
        for (rel in batchFiles) {
            val file = File(root, rel)
            val src = file.readText()
            for ((line, lit) in extractLiterals(src)) {
                if (cyrillic.containsMatchIn(lit)) {
                    hits += "${file.name}:$line \"$lit\""
                }
            }
        }
        assertTrue("Cyrillic literals found:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    private fun extractLiterals(src: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var i = 0
        var line = 1
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c == '\n') {
                line++
                i++
                continue
            }
            if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                i += 2
                while (i < n && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < n && src[i + 1] == '*') {
                i += 2
                while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) {
                    if (src[i] == '\n') line++
                    i++
                }
                i += 2
                continue
            }
            if (c == '\'' ) {
                i++
                while (i < n && src[i] != '\'') {
                    if (src[i] == '\\' && i + 1 < n) i += 2 else i++
                }
                i++
                continue
            }
            if (c == '"') {
                val startLine = line
                if (i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                    i += 3
                    val sb = StringBuilder()
                    while (i + 2 < n && !(src[i] == '"' && src[i + 1] == '"' && src[i + 2] == '"')) {
                        if (src[i] == '\n') line++
                        sb.append(src[i])
                        i++
                    }
                    i += 3
                    out += startLine to sb.toString()
                } else {
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
                    out += startLine to sb.toString()
                }
                continue
            }
            i++
        }
        return out
    }
}
