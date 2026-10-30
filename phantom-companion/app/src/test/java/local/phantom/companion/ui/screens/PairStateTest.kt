package local.phantom.companion.ui.screens

import java.io.File
import local.phantom.companion.R
import local.phantom.companion.data.BodyLadder
import local.phantom.companion.data.PcPairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PairStateTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun Char.isCyrillic(): Boolean =
        Character.UnicodeScript.of(code) == Character.UnicodeScript.CYRILLIC

    private fun sourceFile(relPath: String): File {
        val candidates = listOf(
            File(relPath),
            File("app/$relPath"),
            File("phantom-companion/app/$relPath"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Source file not found: $relPath (cwd=${File(".").absolutePath})")
    }

    @Test
    @Config(qualifiers = "en")
    fun failure_text_is_english_in_english() {
        for (why in PcPairing.Failure.entries) {
            val text = pairFailureText(ctx, why, "x", digits = false)
            assertTrue("Blank text for $why", text.isNotBlank())
            assertFalse("Cyrillic in $why: $text", text.any { it.isCyrillic() })
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun digits_not_found_points_to_qr() {
        val digitsText = pairFailureText(ctx, PcPairing.Failure.NOT_FOUND, "", digits = true)
        val qrText = pairFailureText(ctx, PcPairing.Failure.NOT_FOUND, "", digits = false)
        assertEquals(ctx.getString(R.string.pair_err_digits_not_found), digitsText)
        assertNotEquals(qrText, digitsText)
    }

    @Test
    @Config(qualifiers = "uk")
    fun failure_text_is_ukrainian_in_uk() {
        val text = pairFailureText(ctx, PcPairing.Failure.NOT_PROVEN, "", digits = false)
        assertEquals("Компʼютер не пройшов перевірку.", text)
    }

    @Test
    @Config(qualifiers = "en")
    fun sphere_titles_follow_locale() {
        val enTitles = BodyLadder.Sphere.entries.map { ctx.getString(it.label) }
        assertTrue(enTitles.all { title -> title.isNotBlank() && title.none { it.isCyrillic() } })

        RuntimeEnvironment.setQualifiers("uk")
        assertEquals("Звук і голос", ctx.getString(BodyLadder.Sphere.SOUND.label))
    }

    @Test
    fun vm_has_no_cyrillic_literals_in_claim() {
        val src = sourceFile("src/main/java/local/phantom/companion/vm/PhantomViewModel.kt").readText()
        val body = src.substringAfter("fun claimFromQrJson").substringBefore("\n    fun ")
        assertTrue(body.isNotEmpty())
        assertFalse("Cyrillic found in claimFromQrJson:\n$body", body.any { it.isCyrillic() })
    }

    @Test
    fun pair_destination_shares_the_activity_vm() {
        val src = sourceFile("src/main/java/local/phantom/companion/navigation/PhantomNavGraph.kt").readText()
        val body = src.substringAfter("private fun PairDestination").substringBefore("\n@Composable")
        assertTrue(body.contains("activityPhantomVm()"))
        assertFalse(body.contains("viewModel(factory = PhantomViewModel.Factory)"))
        assertTrue(body.contains("onPaired"))
    }

    @Test
    fun repair_over_an_existing_pairing_still_returns() {
        val src = sourceFile("src/main/java/local/phantom/companion/ui/screens/PairScreen.kt").readText()
        assertTrue(src.contains("state.paired !== pairedOnOpen"))
    }
}
