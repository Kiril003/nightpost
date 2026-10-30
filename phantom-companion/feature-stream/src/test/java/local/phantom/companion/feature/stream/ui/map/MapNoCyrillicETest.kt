package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MapNoCyrillicETest {

    private val batchFiles = listOf(
        "AlertIntel.kt",
        "MapWorldLayers.kt",
        "MapTransitLayers.kt",
        "MapPackTransit.kt",
        "dossier/DossierTransitCard.kt",
    )

    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    private fun moduleRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            val sub = File(dir, "feature-stream")
            if (File(sub, "src/main/java").isDirectory) return sub
            val nested = File(dir, "phantom-companion/feature-stream")
            if (File(nested, "src/main/java").isDirectory) return nested
            dir = dir.parentFile
        }
        fail("module root with src/main/java not found")
        throw AssertionError()
    }

    @Test
    fun `batch E files have no Cyrillic in string literals`() {
        val base = File(moduleRoot(), "src/main/java/local/phantom/companion/feature/stream/ui/map")
        val hits = mutableListOf<String>()
        for (rel in batchFiles) {
            val file = File(base, rel)
            assertTrue("missing $rel", file.isFile)
            val src = file.readText()
            for ((line, lit) in extractStringLiterals(src)) {
                if (cyrillic.containsMatchIn(lit)) {
                    hits += "$rel:$line \"$lit\""
                }
            }
        }
        assertTrue(
            "Cyrillic string literals in batch E:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    private fun extractStringLiterals(src: String): List<Pair<Int, String>> {
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
                var depth = 1
                while (i < n && depth > 0) {
                    if (src[i] == '\n') line++
                    else if (src[i] == '/' && i + 1 < n && src[i + 1] == '*') {
                        depth++
                        i++
                    } else if (src[i] == '*' && i + 1 < n && src[i + 1] == '/') {
                        depth--
                        i++
                    }
                    i++
                }
                continue
            }
            if (c == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                val startLine = line
                i += 3
                val sb = StringBuilder()
                while (i < n) {
                    if (src[i] == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                        i += 3
                        while (i < n && src[i] == '"') {
                            sb.append('"')
                            i++
                        }
                        break
                    }
                    if (src[i] == '\n') line++
                    sb.append(src[i])
                    i++
                }
                out += startLine to sb.toString()
                continue
            }
            if (c == '"') {
                val startLine = line
                i++
                val sb = StringBuilder()
                while (i < n && src[i] != '"' && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < n) {
                        sb.append(src[i])
                        sb.append(src[i + 1])
                        i += 2
                        continue
                    }
                    sb.append(src[i])
                    i++
                }
                if (i < n && src[i] == '"') i++
                out += startLine to sb.toString()
                continue
            }
            if (c == '\'') {
                i++
                while (i < n && src[i] != '\'' && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < n) i += 2 else i++
                }
                if (i < n && src[i] == '\'') i++
                continue
            }
            i++
        }
        return out
    }
}
