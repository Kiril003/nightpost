package local.phantom.companion.core.design.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeSurfacePaletteRatchetTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-design").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root not found") as Nothing
    }

    private val literal = Regex(
        """Color\(\s*0x|Color\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta)\b|parseColor|\b0xFF[0-9A-Fa-f]{6}\b""",
    )

    private fun surfaces(): List<File> {
        val root = repoRoot()
        val map = File(root, "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map")
        val solana = File(root, "core-solana/src/main/java/local/phantom/companion/core/solana")
        val theme = File(root, "app/src/main/java/local/phantom/companion/ui/theme")
        return map.listFiles().orEmpty().filter { it.name.startsWith("MapChrome") || it.name == "MapHomeControls.kt" } +
            File(map, "peers").listFiles().orEmpty().filter { it.extension == "kt" } +
            listOf(File(solana, "PaymentDraft.kt"), File(solana, "PaymentFrame.kt")) +
            listOf(File(theme, "SplashNight.kt"), File(theme, "LivingSun.kt"))
    }

    @Test
    fun `map chrome, person sheets, payment draft and splash carry no colour literals`() {
        val files = surfaces()
        assertTrue("surface files missing: ${files.filterNot { it.isFile }}", files.size >= 12 && files.all { it.isFile })
        val offenders = files.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) ->
                val t = l.trimStart()
                !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) && literal.containsMatchIn(l)
            }.map { (i, l) -> "${f.name}:${i + 1}  ${l.trim().take(80)}" }
        }
        assertTrue("colour literals outside PhantomPalette:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
