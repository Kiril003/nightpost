package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Класифікація й арифметика, на яких стоїть панель.
 *
 * Зразки назв — не вигадані: це дослівні `description` із `mbgl-offline.db`
 * телефона власника, знятого 17.08.2026 (77 пакетів).
 */
class OfflinePackKindTest {

    private fun pack(
        id: Long = 1,
        done: Long = 100,
        need: Long = 100,
        bytes: Long = 1_000,
        kind: PackKind = PackKind.View,
    ) = RegionInfo(
        id = id,
        name = "Ірпінь · 12 × 9 км",
        completedResourceCount = done,
        requiredResourceCount = need,
        completedResourceSizeBytes = bytes,
        isComplete = need > 0 && done == need,
        downloadState = 0,
        minZoom = 10,
        maxZoom = 14,
        kind = kind,
    )

    @Test
    fun `a clock name is recognised, a place name is not`() {
        listOf(
            "регіон · 03.07 12:56",
            "регіон · 12.07 16:16",
            "регіон · 01.08 20:09",
        ).forEach {
            assertTrue("«$it» must be recognised as a clock name", OfflineRegionsManager.isClockName(it))
        }
        listOf(
            "Ірпінь · 12 × 9 км",
            "Житомирська область · 1/3",
            "Коридор: активний маршрут 3/12",
            "Кукільня · 12 × 9 км",
        ).forEach {
            assertFalse("«$it» is a real name and must survive", OfflineRegionsManager.isClockName(it))
        }
    }

    @Test
    fun `a legacy corridor is a corridor, not a scrap`() {
        // Усі 77 пакетів у сховищі власника — `tileregion`: коридори тоді
        // різалися на прямокутні ланки. Тип визначення про них не каже нічого,
        // і класифікація мусить іти з назви, інакше 59 коридорів осідають у
        // «КЛАПТІ» і групова дія «прибрати всі коридори» прибирає нуль.
        listOf(
            "Коридор: активний маршрут 1/10",
            "Коридор: активний маршрут 12/12",
            "коридор · Ірпінь 3/8",
        ).forEach {
            assertTrue("«$it» must read as a corridor", OfflineRegionsManager.isLegacyCorridorName(it))
        }
        listOf("Ірпінь · 12 × 9 км", "регіон · 03.07 12:56", "Житомирська область · 1/3").forEach {
            assertFalse("«$it» is not a corridor", OfflineRegionsManager.isLegacyCorridorName(it))
        }
    }

    @Test
    fun `the estimate stays silent until something has actually arrived`() {
        // «≈0 МБ» is a number nobody measured. Zero means "no estimate", and
        // the state line has a separate wording for that case.
        assertEquals(0L, pack(done = 0, need = 5_000, bytes = 0).estimatedTotalBytes)
        assertEquals(0L, pack(done = 100, need = 0, bytes = 1_000).estimatedTotalBytes)
    }

    @Test
    fun `the estimate extrapolates from what already landed`() {
        // 100 resources cost 1 000 B, 500 are needed ⇒ ≈5 000 B.
        assertEquals(5_000L, pack(done = 100, need = 500, bytes = 1_000).estimatedTotalBytes)
        assertEquals(20, pack(done = 100, need = 500).percent)
    }

    @Test
    fun `percent never runs past a hundred`() {
        assertEquals(100, pack(done = 900, need = 500).percent)
    }
}
