package local.phantom.companion.feature.onboarding.ui

import java.io.File
import local.phantom.companion.core.data.locale.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Перший екран збірки 18: знак Nightpost, один рядок про те, що це, і перелік
 * мов — прапор і власна назва мови, англійська перша й уже обрана.
 *
 * Рух — спокійний: лише прозорість і зсув/масштаб шару (жодної зміни
 * розкладки), і все сідає за ≤ 400 мс; з вимкненою анімацією — одразу.
 */
class LanguageStepTest {

    @Test
    fun `English is the first row and already selected`() {
        val rows = languageRows()
        assertEquals(AppLanguage.offered, rows.map { it.language })
        assertEquals(AppLanguage.EN, rows.first().language)
        assertEquals(listOf(true, false, false), rows.map { it.selected })
        assertEquals("English", rows[0].nativeName)
        assertEquals(AppLanguage.EN.flag, rows[0].flag)
        assertEquals("Українська", rows[1].nativeName)
        assertEquals("Čeština", rows[2].nativeName)
        assertEquals(AppLanguage.CS.flag, rows[2].flag)
    }

    @Test
    fun `exactly one row is selected whichever language is current`() {
        for (l in AppLanguage.offered) {
            val rows = languageRows(selected = l)
            assertEquals(listOf(l), rows.filter { it.selected }.map { it.language })
        }
    }

    @Test
    fun `every entrance lands within 400 ms, the mark first`() {
        val elements = (0 until 6).map { FirstRunMotion.rowAt(it) } +
            listOf(FirstRunMotion.MARK_AT, FirstRunMotion.LINE_AT, FirstRunMotion.ACTION_AT)
        for (at in elements) {
            assertTrue("елемент із затримкою $at мс сідає пізніше 400 мс", at + FirstRunMotion.RISE_MS <= FirstRunMotion.BUDGET_MS)
        }
        assertEquals(0, FirstRunMotion.MARK_AT)
        assertTrue(FirstRunMotion.BUDGET_MS <= 400)
    }

    @Test
    fun `progress runs 0 to 1 and never backwards`() {
        assertEquals(0f, FirstRunMotion.progress(clockMs = 0f, atMs = 40), 0f)
        assertEquals(1f, FirstRunMotion.progress(clockMs = FirstRunMotion.BUDGET_MS.toFloat(), atMs = 40), 0f)
        var last = -1f
        for (t in 0..FirstRunMotion.BUDGET_MS step 10) {
            val p = FirstRunMotion.progress(t.toFloat(), FirstRunMotion.LINE_AT)
            assertTrue(p >= last)
            last = p
        }
    }

    @Test
    fun `with animations off the screen is there at once`() {
        assertEquals(0, FirstRunMotion.clockMs(motionScale = 0f))
        assertEquals(FirstRunMotion.BUDGET_MS, FirstRunMotion.clockMs(motionScale = 1f))
    }

    private fun source(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-onboarding").isDirectory) dir = dir.parentFile
        val f = File(
            dir ?: fail("feature-onboarding/ not found") as Nothing,
            "feature-onboarding/src/main/java/local/phantom/companion/feature/onboarding/ui/LanguageStep.kt",
        )
        assertTrue("екрана вибору мови немає: $f", f.isFile)
        return f.readText()
    }

    @Test
    fun `motion touches only opacity and the layer transform`() {
        val src = source()
        val layers = Regex("""graphicsLayer\s*\{([^}]*)\}""").findAll(src).map { it.groupValues[1] }.toList()
        assertTrue("рух шару не знайдено — сторож дивиться в порожнє", layers.isNotEmpty())
        val allowed = setOf("alpha", "translationX", "translationY", "scaleX", "scaleY")
        for (body in layers) {
            val assigned = Regex("""(\w+)\s*=""").findAll(body).map { it.groupValues[1] }.toSet()
            assertTrue("шар міняє не лише прозорість і зсув: $assigned", allowed.containsAll(assigned))
        }
        for (banned in listOf("animateContentSize", "animateDpAsState", "infiniteRepeatable", "AnimatedVisibility")) {
            assertFalse("$banned рухає розкладку або крутиться вічно", banned in src)
        }
    }

    @Test
    fun `TalkBack hears a radio list of language names, not flags`() {
        val src = source()
        assertTrue("рядок мови — не перемикач-радіо", "Role.RadioButton" in src)
        assertTrue("перелік не згрупований для TalkBack", "selectableGroup()" in src)
        assertTrue("прапор читається вголос («прапор: Велика Британія»)", "clearAndSetSemantics" in src)
    }
}
