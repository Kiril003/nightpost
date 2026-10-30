package local.phantom.companion.feature.stream.ui.map.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-2 — режими стали справжніми, і це таблиця, якою вони виміряні.
 *
 * Той самий інваріант побудови, що й у голосу довіри: кожен ЯРУС на кожному
 * РЕЖИМІ має визначений вердикт, і жоден режим не має права зникнути з екрана
 * мовчки лише тому, що про нього забули написати рядок.
 */
class LensModeTablesTest {

    private val engine = LensEngine()

    private fun frame(scene: SceneMode, zoom: Double = 17.0, alarm: Boolean = false) =
        engine.frame(LensInput(scene = scene, zoom = zoom, ownOblastAlarm = alarm))

    // ── бюджети щільності ─────────────────────────────────────────────────

    @Test
    fun `спокій і огляд не рахують нічого — це і є «як сьогодні»`() {
        for (scene in listOf(SceneMode.CALM, SceneMode.EXPLORE, SceneMode.GUIDE)) {
            for (density in DensityClass.entries) {
                assertEquals(
                    "$scene порахував $density",
                    LensFrame.UNLIMITED,
                    frame(scene).budget(density),
                )
            }
        }
    }

    @Test
    fun `за кермом місць мало, а транспорту нема зовсім`() {
        val drive = frame(SceneMode.DRIVE)
        assertEquals(LensRules.DRIVE_PLACES, drive.budget(DensityClass.PLACE))
        assertEquals(0, drive.budget(DensityClass.TRANSIT))
        assertFalse("стрічка транзиту за кермом гасне", drive.lit(MapTier.TRANSIT))
    }

    @Test
    fun `пішки місць більше, ніж за кермом і в транспорті`() {
        val walk = frame(SceneMode.WALK).budget(DensityClass.PLACE)
        assertTrue(walk > frame(SceneMode.DRIVE).budget(DensityClass.PLACE))
        assertTrue(walk > frame(SceneMode.TRANSIT).budget(DensityClass.PLACE))
    }

    /**
     * Нуль тут був би тим самим блокером гонтлету, заради якого Лінза вчилась
     * кликати шар укриттів: плашка кричить, а на мапі ні аптеки, ні криниці.
     */
    @Test
    fun `під сиреною стійкі місця лишаються на екрані`() {
        val threat = frame(SceneMode.THREAT)
        assertEquals(LensRules.THREAT_PLACES, threat.budget(DensityClass.PLACE))
        assertTrue("стійкі місця мають лишитись", threat.budget(DensityClass.PLACE) > 0)
        assertTrue(threat.lit(MapTier.POI))
    }

    /**
     * Маршрут — це відповідь, яку людина вже отримала. Забрати її з екрана під
     * сиреною означає не «розчистити», а стерти.
     */
    @Test
    fun `загроза лишає маршрут і оператора на місці`() {
        val threat = frame(SceneMode.THREAT)
        assertTrue(threat.lit(MapTier.ROUTE_LINE))
        assertTrue(threat.lit(MapTier.OPERATOR_PIN))
        assertTrue("обраний транзитний маршрут — це теж маршрут", threat.lit(MapTier.TRANSIT))
        assertEquals(LensFrame.UNLIMITED, threat.budget(DensityClass.ROUTE))
        assertEquals(LensFrame.UNLIMITED, threat.budget(DensityClass.OPERATOR))
    }

    @Test
    fun `тривога кличе укриття в будь-якому режимі`() {
        val shelters = local.phantom.companion.feature.stream.geo.MapGroundLayers.SHELTERS
        for (scene in SceneMode.entries) {
            assertTrue(
                "$scene проковтнув виклик укриттів",
                frame(scene, alarm = true).summons.contains(shelters),
            )
        }
    }

    // ── голос довіри в нових режимах ──────────────────────────────────────

    /** За кермом ціна пропущеного напису — не роздратування. */
    @Test
    fun `за кермом слово не стишується навіть на світлофорі`() {
        val stopped = TrustVoiceInput(
            trust = TrustClass.JAMMED,
            scene = SceneMode.DRIVE,
            motion = MotionClass.STILL,
            heldMs = 60 * LensRules.QUIET_DIM_MS,
        )
        assertEquals(TrustVolume.WORDED, engine.voice(stopped))
    }

    // ── інваріант побудови ────────────────────────────────────────────────

    /**
     * Режим має право прибрати з екрана майже все — але не землю під ногами і
     * не точку оператора. Порожній екран лишається легітимним станом; ЕКРАН БЕЗ
     * ЗЕМЛІ — ні, і жоден рядок таблиці не сміє його спричинити.
     */
    @Test
    fun `жоден режим не гасить землю й оператора на жодному зумі`() {
        var checked = 0
        for (scene in SceneMode.entries) {
            for (zoom in listOf(3.0, 11.0, 13.0, 16.5, 19.0)) {
                val f = frame(scene, zoom)
                assertTrue("$scene @ z$zoom згасив ґрунт", f.lit(MapTier.GROUND_CARTOGRAPHY))
                assertTrue("$scene @ z$zoom згасив оператора", f.lit(MapTier.OPERATOR_PIN))
                assertEquals(DensityClass.entries.size, f.budgets.size)
                for (density in DensityClass.entries) {
                    assertTrue("$scene не порахував $density", f.budget(density) >= 0)
                }
                assertTrue("$scene лишив оператора без орба", f.allows(ChromeSlot.LOCATE_ORB))
                checked++
            }
        }
        assertEquals(SceneMode.entries.size * 5, checked)
    }

    /**
     * Ярус, увімкнений із бюджетом нуль, — це шар, який запалено й нічим не
     * наповнено: Лінза каже «місця видно», а на склі порожньо, і жоден лог про
     * це не скаже. Або гасимо ярус, або лишаємо чим його наповнити.
     */
    @Test
    fun `запалений ярус місць ніколи не має нульового бюджету`() {
        for (scene in SceneMode.entries) {
            val f = frame(scene)
            if (f.lit(MapTier.POI)) {
                assertTrue(
                    "$scene запалив POI і зрізав його до нуля",
                    f.budget(DensityClass.PLACE) > 0,
                )
            }
        }
    }

    @Test
    fun `оператора, ґрунт і маршрут не раціонує жоден режим`() {
        for (scene in SceneMode.entries) {
            for (density in LensRules.NEVER_BUDGETED) {
                assertEquals(
                    "$scene раціонував $density",
                    LensFrame.UNLIMITED,
                    frame(scene).budget(density),
                )
            }
        }
    }
}
