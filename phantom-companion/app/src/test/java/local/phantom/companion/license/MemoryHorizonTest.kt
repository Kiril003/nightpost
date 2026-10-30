package local.phantom.companion.license

import local.phantom.companion.core.license.LicenseModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Памʼять — ворота на ЧИТАННЯ, не на видалення. Проба відкриває платне на 14
 * днів, тож на пʼятнадцятий факти, зібрані за ці два тижні, мусять лишитися
 * на пристрої й дочекатися ключа.
 */
class MemoryHorizonTest {

    private val now = 1_800_000_000_000L
    private fun daysAgo(n: Int) = now - n * LicenseModel.DAY_MS

    @Test
    fun `without a key recall stops at thirty days`() {
        val cutoff = MemoryHorizon.cutoffMs(now, entitled = false)

        assertTrue("вчорашнє мало б згадуватися", daysAgo(1) >= cutoff)
        assertTrue("двадцятиденне мало б згадуватися", daysAgo(20) >= cutoff)
        assertTrue("піврічне не мало б згадуватися", daysAgo(180) < cutoff)
        assertEquals(now - MemoryHorizon.FREE_DAYS * LicenseModel.DAY_MS, cutoff)
    }

    @Test
    fun `with a key nothing is out of reach`() {
        val cutoff = MemoryHorizon.cutoffMs(now, entitled = true)

        assertTrue(daysAgo(3650) >= cutoff)
        assertTrue("нульова мітка часу випала з горизонту", 0L >= cutoff)
    }

    @Test
    fun `the conversation keeps working — only the far past goes quiet`() {
        val facts = listOf(
            "сьогоднішнє" to daysAgo(0),
            "тижневе" to daysAgo(7),
            "місячне" to daysAgo(29),
            "весняне" to daysAgo(120),
            "торішнє" to daysAgo(400),
        )

        val free = MemoryHorizon.withinHorizon(facts, now, entitled = false) { it.second }
        val paid = MemoryHorizon.withinHorizon(facts, now, entitled = true) { it.second }

        assertEquals(
            "вільний рівень мусить памʼятати останній місяць, а не нічого",
            listOf("сьогоднішнє", "тижневе", "місячне"),
            free.map { it.first },
        )
        assertEquals(5, paid.size)
    }

    @Test
    fun `the horizon filters, it never empties`() {
        // Ключова відмінність від видалення: список фактів на вході й далі
        // цілий — горизонт лише вирішує, що піде в підказку.
        val facts = List(40) { i -> i to daysAgo(i * 5) }

        val free = MemoryHorizon.withinHorizon(facts, now, entitled = false) { it.second }

        assertTrue("горизонт віддав порожньо — це вже не деградація, а ампутація", free.isNotEmpty())
        assertTrue(free.size < facts.size)
        assertEquals(40, facts.size)
    }

    @Test
    fun `a fact recorded during the trial survives the fifteenth day`() {
        // День 15: проба скінчилася. Факт, складений на другий день проби,
        // вже поза горизонтом згадування — але це ЙОГО дані, і вони на місці.
        val trialFact = daysAgo(13)
        val dayFifteen = MemoryHorizon.cutoffMs(now, entitled = false)

        assertTrue("факт із проби випав за горизонт занадто рано", trialFact >= dayFifteen)
        assertTrue(
            "ключ мусить повертати все прожите",
            trialFact >= MemoryHorizon.cutoffMs(now, entitled = true),
        )
    }
}
