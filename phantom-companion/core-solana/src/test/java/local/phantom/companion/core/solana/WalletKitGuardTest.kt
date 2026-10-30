package local.phantom.companion.core.solana

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Гаманець — гроші, які побачать судді, — мусить говорити мовою Nightpost, а не стоковим
 * Material 3. Сторож читає джерела екрана й аркушів і вимагає: з M3 — лише Text та Icon
 * (усе інше — з набору core-design і токенів v1.2), жодного FontFamily.Monospace (BRAND §7:
 * моно — лише для коду), жодного власного кольору `Color(0x…)` поверх токенів і жодної
 * латки теми. Знак у шапці — Естафета з docs/brand, не стара «P».
 */
class WalletKitGuardTest {

    private fun moduleRoot(): File {
        val here = File("").absoluteFile
        return listOf(here, File(here, "core-solana"), File(here, "phantom-companion/core-solana"))
            .firstOrNull { File(it, "src/main/java/local/phantom/companion/core/solana").isDirectory }
            ?: fail("core-solana module root not found from $here") as Nothing
    }

    private val ui = listOf("WalletActivity.kt", "WalletKit.kt", "AddressSharing.kt")

    private fun source(name: String): String =
        File(moduleRoot(), "src/main/java/local/phantom/companion/core/solana/$name").also { if (!it.isFile) fail("$name is missing") }.readText()

    @Test
    fun `з Material 3 гаманець бере лише Text та Icon`() {
        val allowed = setOf("Text", "Icon")
        val stock = ui.flatMap { name ->
            Regex("""import androidx\.compose\.material3\.(\w+)""").findAll(source(name))
                .map { it.groupValues[1] }.filter { it !in allowed }.map { "$name: $it" }.toList()
        }
        assertEquals("стокові M3 у гаманці: $stock", emptyList<String>(), stock)
        val themed = ui.filter { source(it).contains("MaterialTheme") }
        assertEquals("латка теми M3 у гаманці: $themed", emptyList<String>(), themed)
        assertTrue("WalletTheme.kt повернувся", !File(moduleRoot(), "src/main/java/local/phantom/companion/core/solana/WalletTheme.kt").exists())
    }

    @Test
    fun `моноширинного шрифту й власних кольорів немає`() {
        for (name in ui) {
            val src = source(name)
            assertTrue("$name: FontFamily.Monospace", !src.contains("FontFamily.Monospace"))
            assertTrue("$name: власний колір поверх токенів", !Regex("""Color\(\s*0x""").containsMatchIn(src))
        }
    }

    @Test
    fun `знак у шапці — Естафета з бренду`() {
        val mark = File(moduleRoot(), "src/main/res/drawable/ic_nightpost_symbol.xml").readText()
        val brand = File(moduleRoot(), "../../docs/brand/assets/android/res/drawable/ic_nightpost_symbol.xml")
        if (!brand.isFile) fail("brand source ${brand.path} is missing")
        assertEquals("копія знака розійшлася з docs/brand", brand.readText(), mark)
        assertTrue(mark.contains("M11 2A8 8 0 0 0 11 18ZM13 6A8 8 0 0 1 13 22Z"))
        assertTrue("шапка малює не той знак", source("WalletKit.kt").contains("R.drawable.ic_nightpost_symbol"))
    }

    @Test
    fun `на виду гаманця немає старого імені`() {
        for (dir in listOf("values-uk", "values")) {
            val xml = File(moduleRoot(), "src/main/res/$dir/strings.xml").readText()
            val shown = Regex(""">([^<]*)<""").findAll(xml).map { it.groupValues[1] }.filter { it.contains("phantom", ignoreCase = true) }.toList()
            assertEquals("$dir: $shown", emptyList<String>(), shown)
        }
    }
}
