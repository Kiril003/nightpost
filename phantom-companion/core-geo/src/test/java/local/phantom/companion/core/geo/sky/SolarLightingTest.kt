package local.phantom.companion.core.geo.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Показник світла для інструмента `solar.lighting` — і доказ, що він НЕ веде
 * власної астрономії. До ATLAS-1 вів: «Ω-3 TERRAIN» рахував свою ефемериду, і
 * ЇЇ число їхало у відповідь помічника.
 */
class SolarLightingTest {

    private val lat = 49.97
    private val lon = 28.79

    /** 2026-08-23T00:00:00Z. Цього дня в Житомирі сонце проходить від ≈ −28°
     *  до ≈ +51°, тобто всі пʼять смуг прози справді трапляються. */
    private val day = 1_787_443_200_000L

    private fun readings() = (0 until 24 * 60 step 2).map { SolarLighting.at(lat, lon, day + it * 60_000L) }

    @Test
    fun `every number comes from the one almanac`() {
        for (hour in 0 until 24) {
            val t = day + hour * 3_600_000L
            val reading = SolarLighting.at(lat, lon, t)
            val sky = SolarAlmanac.sky(t, lat, lon)
            assertEquals("година $hour", sky.elevationDeg, reading.elevationDeg, 1e-12)
            assertEquals("година $hour", sky.azimuthDeg, reading.azimuthDeg, 1e-12)
            assertEquals("година $hour", sky.phase, reading.phase)
        }
    }

    /**
     * Освітленість мусить рости разом із сонцем — і на межах смуг теж.
     *
     * Успадкована крива тут ламалась: на переході в денну смугу відповідь
     * падала з 25 000 до 10 450 лк, тобто сонце піднімалось, а помічник казав,
     * що потемніло.
     */
    @Test
    fun `the light never falls while the sun climbs`() {
        for ((a, b) in readings().sortedBy { it.elevationDeg }.zipWithNext()) {
            assertTrue(
                "сонце піднялось з ${a.elevationDeg}° до ${b.elevationDeg}°, а світло впало: ${a.luxDirect} → ${b.luxDirect}",
                b.luxDirect >= a.luxDirect,
            )
        }
    }

    /** Уночі не нуль: нуль читався б як «датчик зламано», а не «темно». */
    @Test
    fun `even the darkest hour reports light it can defend`() {
        val darkest = readings().minByOrNull { it.elevationDeg }!!
        assertEquals(SkyPhase.NIGHT, darkest.phase)
        assertTrue("нічна освітленість — рівний нуль", darkest.luxDirect > 0f)
    }

    /** Кожна смуга прози мусить цілком лежати всередині однієї фази — інакше в
     *  застосунку дві системи координат замість однієї. */
    @Test
    fun `the words never contradict the phase beside them`() {
        val seen = mutableSetOf<String>()
        for (r in readings()) {
            val head = r.descriptionUk.substringBefore(':')
            seen += head
            val expected = when (head) {
                "Денне світло" -> SkyPhase.DAY
                "Золота година" -> SkyPhase.GOLDEN
                "Сутінки" -> SkyPhase.DUSK
                "Астрономічні сутінки", "Ніч" -> SkyPhase.NIGHT
                else -> error("невідома смуга прози: $head")
            }
            assertEquals("на ${r.elevationDeg}° слова й фаза розійшлися", expected, r.phase)
        }
        assertEquals("не всі смуги трапились за добу — перевірка нічого не боронить", 5, seen.size)
    }
}
