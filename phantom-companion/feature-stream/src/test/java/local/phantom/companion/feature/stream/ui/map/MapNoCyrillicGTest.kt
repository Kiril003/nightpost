package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class MapNoCyrillicGTest {

    private val batchFiles = listOf(
        "nav/GuidanceChrome.kt",
        "LiveMapFeeds.kt",
        "MapStyleLayers.kt",
        "nav/RenderedRoads.kt",
        "nav/GenerativeOverlays.kt",
        "POIOverlay.kt",
    )

    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    @Test
    fun batchGHasNoCyrillicStringLiterals() {
        val root = moduleRoot()
        val base = File(root, "src/main/java/local/phantom/companion/feature/stream/ui/map")
        val hits = mutableListOf<String>()
        for (rel in batchFiles) {
            val file = File(base, rel)
            val stripped = stripComments(file.readText())
            for ((line, lit) in stringLiterals(stripped)) {
                if (cyrillic.containsMatchIn(lit)) {
                    hits += "$rel:$line \"$lit\""
                }
            }
        }
        assertEquals(emptyList<String>(), hits)
    }

    private fun moduleRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) {
            if (File(dir, "feature-stream/src/main/java").isDirectory) {
                return File(dir, "feature-stream")
            }
            dir = dir.parentFile
        }
        return requireNotNull(dir) { "module root not found" }
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var state = 0
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when (state) {
                0 -> when {
                    c == '/' && next == '/' -> {
                        state = 1
                        i += 2
                        continue
                    }
                    c == '/' && next == '*' -> {
                        state = 2
                        i += 2
                        continue
                    }
                    c == '"' && next == '"' && i + 2 < src.length && src[i + 2] == '"' -> {
                        out.append("\"\"\"")
                        state = 4
                        i += 3
                        continue
                    }
                    c == '"' -> {
                        out.append(c)
                        state = 3
                    }
                    else -> out.append(c)
                }
                1 -> if (c == '\n') {
                    out.append('\n')
                    state = 0
                }
                2 -> if (c == '*' && next == '/') {
                    state = 0
                    i += 2
                    continue
                } else if (c == '\n') {
                    out.append('\n')
                }
                3 -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < src.length) {
                        out.append(src[i + 1])
                        i += 2
                        continue
                    } else if (c == '"') {
                        state = 0
                    }
                }
                4 -> if (c == '"' && next == '"' && i + 2 < src.length && src[i + 2] == '"') {
                    out.append("\"\"\"")
                    state = 0
                    i += 3
                    continue
                } else {
                    out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }

    private fun stringLiterals(src: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var i = 0
        var line = 1
        while (i < src.length) {
            val c = src[i]
            if (c == '\n') {
                line++
                i++
                continue
            }
            if (c == '"' && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"') {
                val startLine = line
                val buf = StringBuilder()
                i += 3
                while (i < src.length) {
                    if (src[i] == '"' && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"') {
                        i += 3
                        break
                    }
                    if (src[i] == '\n') line++
                    buf.append(src[i])
                    i++
                }
                out += startLine to buf.toString()
                continue
            }
            if (c == '"') {
                val startLine = line
                val buf = StringBuilder()
                i++
                while (i < src.length && src[i] != '"') {
                    if (src[i] == '\\' && i + 1 < src.length) {
                        buf.append(src[i]).append(src[i + 1])
                        i += 2
                        continue
                    }
                    if (src[i] == '\n') line++
                    buf.append(src[i])
                    i++
                }
                i++
                out += startLine to buf.toString()
                continue
            }
            i++
        }
        return out
    }
}
