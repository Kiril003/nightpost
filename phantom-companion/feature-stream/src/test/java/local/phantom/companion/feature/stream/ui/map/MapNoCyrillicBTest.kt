package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapNoCyrillicBTest {

    private val batchFiles = listOf(
        "src/main/java/local/phantom/companion/feature/stream/ui/map/dossier/DossierCategory.kt",
        "src/main/java/local/phantom/companion/feature/stream/ui/map/dossier/DossierBriefing.kt",
        "src/main/java/local/phantom/companion/feature/stream/ui/map/dossier/DossierProximity.kt",
        "src/main/java/local/phantom/companion/feature/stream/ui/map/dossier/DossierInside.kt",
    )

    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    private fun moduleRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            val sub = File(dir, "feature-stream")
            if (File(sub, "src/main/java").isDirectory) return sub
            val sub2 = File(dir, "phantom-companion/feature-stream")
            if (File(sub2, "src/main/java").isDirectory) return sub2
            dir = dir.parentFile
        }
        error("module root with src/main/java not found")
    }

    @Test
    fun `batch B files contain no Cyrillic string literals`() {
        val root = moduleRoot()
        val offenders = mutableListOf<String>()
        for (rel in batchFiles) {
            val file = File(root, rel)
            assertTrue("Missing batch file: $rel", file.isFile)
            val shortName = file.name
            for ((line, literal) in extractStringLiterals(file.readText())) {
                if (cyrillic.containsMatchIn(literal)) {
                    offenders += "$shortName:$line \"$literal\""
                }
            }
        }
        assertEquals(
            "Cyrillic literals found in batch B:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    private fun extractStringLiterals(source: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var i = 0
        var line = 1
        val n = source.length
        while (i < n) {
            val c = source[i]
            if (c == '\n') {
                line++
                i++
                continue
            }
            if (c == '/' && i + 1 < n && source[i + 1] == '/') {
                i += 2
                while (i < n && source[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < n && source[i + 1] == '*') {
                i += 2
                while (i < n && !(source[i] == '*' && i + 1 < n && source[i + 1] == '/')) {
                    if (source[i] == '\n') line++
                    i++
                }
                if (i < n) i += 2
                continue
            }
            if (c == '\'' ) {
                i++
                while (i < n && source[i] != '\'' && source[i] != '\n') {
                    if (source[i] == '\\' && i + 1 < n) i += 2 else i++
                }
                if (i < n && source[i] == '\'') i++
                continue
            }
            if (c == '"') {
                val startLine = line
                if (i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"') {
                    i += 3
                    val sb = StringBuilder()
                    while (i < n && !(source[i] == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"')) {
                        if (source[i] == '\n') line++
                        sb.append(source[i])
                        i++
                    }
                    if (i < n) i += 3
                    out += startLine to sb.toString()
                } else {
                    i++
                    val sb = StringBuilder()
                    while (i < n && source[i] != '"' && source[i] != '\n') {
                        if (source[i] == '\\' && i + 1 < n) {
                            sb.append(source[i])
                            sb.append(source[i + 1])
                            i += 2
                        } else {
                            sb.append(source[i])
                            i++
                        }
                    }
                    if (i < n && source[i] == '"') i++
                    out += startLine to sb.toString()
                }
                continue
            }
            i++
        }
        return out
    }
}
