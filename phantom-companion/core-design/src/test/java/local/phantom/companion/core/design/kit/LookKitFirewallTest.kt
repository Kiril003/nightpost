package local.phantom.companion.core.design.kit

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookKitFirewallTest {

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "core-design/src/main").isDirectory }

    private fun kitFiles(): List<File> {
        val dir = File(root(), "core-design/src/main/java/local/phantom/companion/core/design/kit")
        assertTrue("kit directory missing: $dir", dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().also { assertTrue("kit directory has no files", it.isNotEmpty()) }
    }

    private fun source(): String = kitFiles().joinToString("\n") { it.readText() }

    private fun body(src: String, name: String): String {
        val at = src.indexOf("fun $name(")
        assertTrue("fun $name not found", at >= 0)
        val next = src.indexOf("\n@Composable", at)
        return if (next < 0) src.substring(at) else src.substring(at, next)
    }

    @Test fun theKitIsCoveredByTheRatchetsThatScanCoreDesign() {
        val tests = File(root(), "core-design/src/test/java/local/phantom/companion/core/design")
        for (name in listOf("layout/TouchTargetFirewallTest.kt", "theme/ShapeDriftFirewallTest.kt", "theme/AccentAsInkFirewallTest.kt", "i18n/HardcodedCyrillicFirewallTest.kt")) {
            assertTrue("$name no longer scans core-design/src/main", File(tests, name).readText().contains("\"core-design/src/main"))
        }
    }

    @Test fun onlyTokensNoRawNumbersColoursOrEffects() {
        val src = source()
        for ((why, rx) in listOf(
            "raw dp or sp literal" to Regex("""\b\d+(\.\d+)?\.(dp|sp)\b"""),
            "raw colour" to Regex("""Color\(\s*0x"""),
            "raw corner" to Regex("""RoundedCornerShape\(\s*\d"""),
            "gradient" to Regex("""(?i)gradient|Brush\."""),
            "blur or shadow" to Regex("""\.blur\(|\.shadow\(|shadowElevation|\.glow"""),
            "dashed line" to Regex("""PathEffect|dashPathEffect|Stroke\("""),
            "cyrillic" to Regex("""[Ѐ-ӿ]"""),
        )) {
            assertFalse("kit has a $why: ${rx.find(src)?.value}", rx.containsMatchIn(src))
        }
    }

    @Test fun theKitReadsTheTokenSetsItNames() {
        val src = source()
        for (token in listOf("PhantomRadius.chip", "PhantomRadius.sheet", "PhantomText.caption", "PhantomText.title2", "LocalPhantomColors", "PhantomSize.buttonM", "PhantomSize.buttonL", "PhantomSize.listrow", "lineStrong", "overlay")) {
            assertTrue("kit does not use $token", src.contains(token))
        }
    }

    @Test fun aStatusChipIsNeverFilledGreenOrAmber() {
        val chip = body(source(), "StatusChip")
        for (banned in listOf("success", "accent", "warning", "danger")) assertFalse("StatusChip uses $banned", chip.contains(banned))
        assertTrue(chip.contains("PhantomRadius.chip"))
    }

    @Test fun amountsShowSuccessOnlyForIncomingAndUseTabularFigures() {
        val amount = body(source(), "AmountText")
        assertTrue(amount.contains("c.success"))
        assertTrue(amount.contains("tnum"))
        assertEquals("success only for the incoming branch", 1, Regex("""c\.success""").findAll(amount).count())
    }

    @Test fun theSheetHasAHandleAHeaderAndATonalClose() {
        val sheet = body(source(), "SheetFrame")
        for (needed in listOf("HandleWidth", "HandleHeight", "SheetHeader", "CloseSize", "PhantomRadius.sheet", "Gravity.BOTTOM")) {
            assertTrue("SheetFrame lacks $needed", sheet.contains(needed))
        }
    }
}
