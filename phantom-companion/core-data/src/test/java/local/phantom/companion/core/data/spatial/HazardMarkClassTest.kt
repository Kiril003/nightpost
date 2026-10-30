package local.phantom.companion.core.data.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Таксономія знаків мусить лишатись сумісною НАЗАД: у шарі лежать рядки,
 * записані трьома руками за різні місяці, і жоден із них не знає про enum.
 * Мітка, що перестала розвʼязуватись, — це мітка, яка мовчки змінила клас на
 * екрані оператора.
 */
class HazardMarkClassTest {

    @Test
    fun `the kinds the sheet and the live plane have always written`() {
        // «блокпост» уже ніхто не пише (див. LiveMarksVocabularyTest), але
        // клас лишився для КПП з OSM.
        assertEquals(MARK_CLASS_CHECKPOINT, hazardMarkClass("блокпост"))
        assertEquals(MARK_CLASS_CRASH, hazardMarkClass("ДТП"))
    }

    @Test
    fun `world report's free vocabulary still lands somewhere honest`() {
        assertEquals(MARK_CLASS_CRASH, hazardMarkClass("дтп"))
        assertEquals(MARK_CLASS_CAMERA, hazardMarkClass("камера"))
        assertEquals(MARK_CLASS_SHELTER, hazardMarkClass("укриття"))
        // «ремонт», «затор», «інше» власного тіла не мають — загальний клас
        // небезпеки не бреше про них, на відміну від значка аварії.
        assertEquals(MARK_CLASS_DANGER, hazardMarkClass("ремонт"))
        assertEquals(MARK_CLASS_DANGER, hazardMarkClass("затор"))
        assertEquals(MARK_CLASS_DANGER, hazardMarkClass(""))
    }

    @Test
    fun `case never decides the class`() {
        MARK_CLASSES.forEach { klass ->
            val sample = when (klass) {
                MARK_CLASS_GATE -> "Шлагбаум"
                MARK_CLASS_CHECKPOINT -> "БЛОКПОСТ"
                MARK_CLASS_CRASH -> "Аварія"
                MARK_CLASS_CAMERA -> "Камера швидкості"
                MARK_CLASS_SHELTER -> "Сховище"
                else -> "обстріл"
            }
            assertEquals(klass, hazardMarkClass(sample))
        }
    }

    @Test
    fun `a checkpoint is not a gate and never gets thinned as one`() {
        assertEquals(MARK_CLASS_CHECKPOINT, barrierMarkClass("checkpoint"))
        assertEquals(MARK_CLASS_CHECKPOINT, barrierMarkClass("border_control"))
        assertEquals(MARK_CLASS_CHECKPOINT, barrierMarkClass("toll_booth"))
    }

    @Test
    fun `courtyard furniture is gate-class, whatever OSM calls it`() {
        listOf("gate", "lift_gate", "swing_gate", "sliding_gate", "bollard", "block", "chain")
            .forEach { assertEquals(it, MARK_CLASS_GATE, barrierMarkClass(it)) }
    }

    @Test
    fun `every class the renderer knows is reachable from data`() {
        val reached = listOf("ворота", "блокпост", "патруль", "ДТП", "камера", "укриття", "обстріл")
            .map(::hazardMarkClass)
            .toSet()
        assertTrue("недосяжні класи: ${MARK_CLASSES - reached}", reached.containsAll(MARK_CLASSES))
    }
}
