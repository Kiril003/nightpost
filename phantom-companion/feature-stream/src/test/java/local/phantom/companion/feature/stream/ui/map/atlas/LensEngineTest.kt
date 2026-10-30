package local.phantom.companion.feature.stream.ui.map.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The initial rule set is a transcription of what the map does on the device
 * today. Every gate below is one that exists somewhere in `MapCanvas`,
 * `MapPoiSearch`, `MapOverlayLayers` or the transit overlay right now — so
 * these tests are simultaneously the Lens's spec and the proof that 0b's
 * wiring changes nothing the operator can see.
 */
class LensEngineTest {

    private val engine = LensEngine()

    private fun frame(
        scene: SceneMode = SceneMode.EXPLORE,
        zoom: Double = 15.0,
        motion: MotionClass = MotionClass.UNKNOWN,
        ownOblastAlarm: Boolean = false,
        ownOblastKnown: Boolean = false,
        activeAlarmCount: Int = 0,
        skyFeedSilent: Boolean = false,
        pinnedPlaces: Boolean = false,
        alertsVisible: Boolean = true,
        radarVisible: Boolean = false,
        dayPhase: DayPhase = DayPhase.DAY,
        indoor: Boolean = false,
        dossierOpen: Boolean = false,
        countryPack: Boolean = false,
        terrain: Boolean = false,
    ) = engine.frame(
        LensInput(
            scene = scene,
            zoom = zoom,
            motion = motion,
            ownOblastAlarm = ownOblastAlarm,
            ownOblastKnown = ownOblastKnown,
            activeAlarmCount = activeAlarmCount,
            skyFeedSilent = skyFeedSilent,
            pinnedPlaces = pinnedPlaces,
            alertsVisible = alertsVisible,
            radarVisible = radarVisible,
            dayPhase = dayPhase,
            indoorPlanPresent = indoor,
            dossierOpen = dossierOpen,
            countryPackPresent = countryPack,
            terrainEntitlement = terrain,
        ),
    )

    // ── today's zoom gates ────────────────────────────────────────────────

    @Test
    fun `POI needs zoom 13, unless a flood is pinned`() {
        assertFalse(frame(zoom = 12.99).lit(MapTier.POI))
        assertTrue(frame(zoom = 13.0).lit(MapTier.POI))
        // MapPoiSearch stands its bbox auto-fetch down while a «поруч» set is
        // pinned, so that set outlives the zoom that found it. Without this the
        // Lens would blink out a flood the operator asked for at city scale.
        assertTrue(frame(zoom = 11.0, pinnedPlaces = true).lit(MapTier.POI))
    }

    @Test
    fun `the transit ribbon is not zoom-gated`() {
        // Against the skeleton's own note: this tier is the SELECTED itinerary's
        // ribbon, and a chosen route is never hidden for standing too far back.
        // The z13 rule the note meant belongs to transit VEHICLES, which are
        // still a Compose overlay with a gate of their own.
        assertTrue(frame(zoom = 6.0).lit(MapTier.TRANSIT))
        assertTrue(frame(zoom = 13.0).lit(MapTier.TRANSIT))
    }

    @Test
    fun `indoor lights at 16 point 5, or whenever a dossier is open`() {
        assertFalse(frame(zoom = 16.4).lit(MapTier.INDOOR_PLAN))
        assertTrue(frame(zoom = 16.5).lit(MapTier.INDOOR_PLAN))
        assertTrue(
            "an open dossier is a question about this building",
            frame(zoom = 11.0, dossierOpen = true).lit(MapTier.INDOOR_PLAN),
        )
    }

    // ── today's capability gates ──────────────────────────────────────────

    @Test
    fun `alerts obey the operator's toggle, not the sky`() {
        assertTrue(frame(alertsVisible = true).lit(MapTier.ALERT_WASH))
        assertFalse(frame(alertsVisible = false, ownOblastAlarm = true).lit(MapTier.ALERT_WASH))
    }

    @Test
    fun `radar is off until asked for and caps at z10`() {
        assertFalse(frame().lit(MapTier.RADAR))
        val on = frame(radarVisible = true)
        assertTrue(on.lit(MapTier.RADAR))
        assertEquals(LensRules.RADAR_TILE_MAX_ZOOM, on.detailCap(MapTier.RADAR)!!, 0.0)
        assertNull(on.detailCap(MapTier.POI))
    }

    @Test
    fun `hillshade and extrusions sit behind the terrain entitlement`() {
        val free = frame(terrain = false)
        assertFalse(free.lit(MapTier.HILLSHADE))
        assertFalse(free.lit(MapTier.BUILDING_EXTRUSION))

        val paid = frame(terrain = true)
        assertTrue(paid.lit(MapTier.HILLSHADE))
        assertTrue(paid.lit(MapTier.BUILDING_EXTRUSION))
    }

    @Test
    fun `an indoor plan hides the extrusions it would be trapped inside`() {
        assertFalse(frame(terrain = true, indoor = true).lit(MapTier.BUILDING_EXTRUSION))
    }

    @Test
    fun `the coverage hatch is never behind a licence`() {
        for (zoom in listOf(3.0, 10.0, 15.0, 20.0)) {
            assertTrue(
                "«тут нічого не завантажено» is a fact a licence may not hide",
                frame(terrain = false, zoom = zoom).lit(MapTier.COVERAGE_HATCH),
            )
        }
        assertTrue(LensRules.DEFAULT.tier(MapTier.COVERAGE_HATCH).requires.isEmpty())
    }

    @Test
    fun `the operator's own dot is unconditional`() {
        for (scene in SceneMode.entries) {
            for (zoom in listOf(0.0, 6.0, 13.0, 18.0, 22.0)) {
                assertTrue(
                    "$scene @ $zoom lost the operator pin",
                    frame(scene = scene, zoom = zoom).lit(MapTier.OPERATOR_PIN),
                )
            }
        }
    }

    @Test
    fun `the EW trail is installed but never lit`() {
        assertFalse(frame().lit(MapTier.EW_TOPOLOGY))
        assertTrue(
            "bringing it back must be one input field, not a code change",
            LensEngine().frame(LensInput(ewTrailEnabled = true)).lit(MapTier.EW_TOPOLOGY),
        )
    }

    // ── chrome slots ──────────────────────────────────────────────────────

    @Test
    fun `the zoom deck and the tools capsule stand down during guidance`() {
        assertTrue(frame(scene = SceneMode.EXPLORE).allows(ChromeSlot.ZOOM_DECK))
        assertFalse(frame(scene = SceneMode.GUIDE).allows(ChromeSlot.ZOOM_DECK))
        assertFalse(frame(scene = SceneMode.GUIDE).allows(ChromeSlot.TOOLS_CAPSULE))
        assertFalse(frame(scene = SceneMode.GUIDE).lit(MapTier.CHROME_TOOLS))
    }

    @Test
    fun `the maneuver banner belongs to guidance alone`() {
        assertTrue(frame(scene = SceneMode.GUIDE).allows(ChromeSlot.MANEUVER_BANNER))
        assertFalse(frame(scene = SceneMode.CALM).allows(ChromeSlot.MANEUVER_BANNER))
        assertFalse(frame(scene = SceneMode.EXPLORE).allows(ChromeSlot.ETA_STRIP))
    }

    @Test
    fun `the alert pill silences only when there is nothing to say`() {
        // Transcribed from AlertBanner's own early return, NOT from the
        // skeleton's note about it: the banner returns early only when the map
        // neither knows the operator's oblast nor has a single alert to count.
        // A quiet, NAMED oblast still gets a pill that says so — and that is
        // the state it spends most days in.
        assertFalse(frame().allows(ChromeSlot.ALERT_PILL))
        assertTrue(frame(ownOblastKnown = true).allows(ChromeSlot.ALERT_PILL))
        assertTrue(frame(activeAlarmCount = 3).allows(ChromeSlot.ALERT_PILL))
        assertTrue(frame(ownOblastAlarm = true).allows(ChromeSlot.ALERT_PILL))
    }

    @Test
    fun `a sky nobody has read yet is not silence`() {
        // Холодний старт: фіксу ще нема (область невідома), тривог не
        // нараховано — і стрічку не прочитали ЖОДНОГО разу, тобто
        // `SkyVerdict.NOFEED`. Два перші факти виглядають як «нема про що
        // говорити», але третій каже, що це «не знаю», і `AirAlertWatch.kt`
        // має на нього власний закон: мовчання мережі ніколи не читається як
        // безпека. Пігулка тут мусить бути на склі, бо за нею — маршрут до
        // укриття, і зникала вона рівно тоді, коли телефон щойно ввімкнули.
        //
        // Мовчазна стрічка над відомою областю нічого не забирає: пігулку вже
        // пускала перша здатність, і третя не має права стати умовою.
        assertTrue(frame(skyFeedSilent = true).allows(ChromeSlot.ALERT_PILL))
        assertTrue(
            frame(ownOblastKnown = true, skyFeedSilent = true).allows(ChromeSlot.ALERT_PILL),
        )
        // І дзеркало лишається дзеркалом: без жодного з трьох фактів пігулка
        // мовчить, як і рання відмова самої `AlertBanner`.
        assertFalse(frame(skyFeedSilent = false).allows(ChromeSlot.ALERT_PILL))
    }

    @Test
    fun `the indoor switcher needs an indoor plan`() {
        assertFalse(frame(zoom = 18.0).allows(ChromeSlot.INDOOR_SWITCHER))
        assertTrue(frame(zoom = 18.0, indoor = true).allows(ChromeSlot.INDOOR_SWITCHER))
    }

    @Test
    fun `locate is never taken away`() {
        for (scene in SceneMode.entries) {
            assertTrue(scene.name, frame(scene = scene).allows(ChromeSlot.LOCATE_ORB))
        }
    }

    // ── budgets ───────────────────────────────────────────────────────────

    @Test
    fun `the live scenes ration nothing — 0b must change nothing visible`() {
        for (scene in listOf(SceneMode.CALM, SceneMode.EXPLORE, SceneMode.GUIDE)) {
            val f = frame(scene = scene, zoom = 17.0)
            for (density in DensityClass.entries) {
                assertEquals(
                    "$scene rationed $density",
                    LensFrame.UNLIMITED,
                    f.budget(density),
                )
            }
        }
    }

    /** ATLAS-2 виміряв самі числа — тут лишається доказ, що вони з ДАНИХ. */
    @Test
    fun `a mode rations from data alone`() {
        val drive = frame(scene = SceneMode.DRIVE, zoom = 17.0)
        assertEquals(LensRules.DRIVE_PLACES, drive.budget(DensityClass.PLACE))
        assertEquals(0, drive.budget(DensityClass.TRANSIT))

        val threat = frame(scene = SceneMode.THREAT, zoom = 17.0)
        assertEquals(LensRules.THREAT_PLACES, threat.budget(DensityClass.PLACE))
        assertEquals(0, threat.budget(DensityClass.ANNOTATION))
    }

    @Test
    fun `the ground, the route and the operator are never rationed`() {
        for (scene in SceneMode.entries) {
            val f = frame(scene = scene, zoom = 17.0)
            for (density in LensRules.NEVER_BUDGETED) {
                assertEquals(
                    "$scene rationed $density",
                    LensFrame.UNLIMITED,
                    f.budget(density),
                )
            }
        }
    }

    // ── the table is total ────────────────────────────────────────────────

    @Test
    fun `every tier and every chrome slot has a rule`() {
        MapTier.entries.forEach { LensRules.DEFAULT.tier(it) }
        ChromeSlot.entries.forEach { LensRules.DEFAULT.chrome(it) }
    }

    @Test
    fun `every mode times every band resolves into a well-formed frame`() {
        var frames = 0
        for (scene in SceneMode.entries) {
            for (band in ZoomBand.entries) {
                for (motion in MotionClass.entries) {
                    val f = engine.frame(
                        LensInput(scene = scene, zoom = band.minZoom, motion = motion),
                    )
                    assertEquals(scene, f.scene)
                    assertEquals(band, f.band)
                    assertEquals(DensityClass.entries.size, f.budgets.size)
                    assertTrue(f.tiers.all { it in MapTier.entries })
                    frames++
                }
            }
        }
        assertEquals(
            SceneMode.entries.size * ZoomBand.entries.size * MotionClass.entries.size,
            frames,
        )
    }

    @Test
    fun `adding a mode is data, not a branch`() {
        val strict = LensRules(
            tierRules = MapTier.entries.map {
                if (it == MapTier.POI) TierRule(it, scenes = SceneMode.ALL - SceneMode.THREAT)
                else TierRule(it)
            },
            chromeRules = ChromeSlot.entries.map { ChromeRule(it) },
            budgetRules = listOf(BudgetRule(DensityClass.PLACE, 3, scenes = setOf(SceneMode.THREAT))),
        )

        val threat = LensEngine(strict).frame(LensInput(scene = SceneMode.THREAT, zoom = 18.0))
        assertFalse(threat.lit(MapTier.POI))
        assertEquals(3, threat.budget(DensityClass.PLACE))

        val calm = LensEngine(strict).frame(LensInput(scene = SceneMode.CALM, zoom = 18.0))
        assertTrue(calm.lit(MapTier.POI))
        assertEquals(LensFrame.UNLIMITED, calm.budget(DensityClass.PLACE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a rule table with a hole is rejected at construction`() {
        LensRules(
            tierRules = MapTier.entries.filterNot { it == MapTier.POI }.map { TierRule(it) },
            chromeRules = ChromeSlot.entries.map { ChromeRule(it) },
            budgetRules = emptyList(),
        )
    }

    @Test
    fun `an empty screen is a legitimate frame`() {
        assertTrue(LensFrame.DARK.tiers.isEmpty())
        assertTrue(LensFrame.DARK.chrome.isEmpty())
        assertTrue(LensFrame.DARK.summons.isEmpty())
        assertEquals(LensFrame.UNLIMITED, LensFrame.DARK.budget(DensityClass.PLACE))
    }

    // ── сирена кличе укриття ──────────────────────────────────────────────

    private val shelters = local.phantom.companion.feature.stream.geo.MapGroundLayers.SHELTERS

    /**
     * Блокер гонтлету (киянка з дитиною): «укриттів на мапі немає, хоча
     * застосунок сам показує тривогу». Плашка кричала, а шар чекав пальця.
     */
    @Test
    fun `an alarm over the operator's own oblast summons the shelters`() {
        assertTrue(frame(ownOblastAlarm = true).summons.contains(shelters))
    }

    /** Тихий день нічого не качає — це мережа й батарея, не тільки екран. */
    @Test
    fun `a quiet sky summons nothing`() {
        assertTrue(frame().summons.isEmpty())
        assertTrue(frame(ownOblastKnown = true).summons.isEmpty())
    }

    /**
     * «Десь у країні гучно» — не привід качати укриття навколо оператора.
     * Плашка рахує всю країну; цей рядок питає САМЕ його область.
     */
    @Test
    fun `loud somewhere else is not loud here`() {
        assertTrue(frame(activeAlarmCount = 5, ownOblastKnown = true).summons.isEmpty())
    }

    /** Відбій знімає вимогу — далі шар доживає власний TTL, без «прибрати». */
    @Test
    fun `the all-clear withdraws the summons`() {
        assertTrue(frame(ownOblastAlarm = true).summons.isNotEmpty())
        assertTrue(frame(ownOblastAlarm = false, activeAlarmCount = 1).summons.isEmpty())
    }

    /** Тривога не питає, як близько стоїть камера. */
    @Test
    fun `the summons does not depend on zoom or scene`() {
        SceneMode.entries.forEach { scene ->
            listOf(4.0, 11.0, 18.0).forEach { zoom ->
                assertTrue(
                    "$scene @ z$zoom проковтнув сирену",
                    frame(scene = scene, zoom = zoom, ownOblastAlarm = true).summons.contains(shelters),
                )
            }
        }
    }

    /** Виклик без умови — це запит до Overpass на кожне відкриття мапи. */
    @Test(expected = IllegalArgumentException::class)
    fun `an unconditional summon is rejected at construction`() {
        LensRules(
            tierRules = MapTier.entries.map { TierRule(it) },
            chromeRules = ChromeSlot.entries.map { ChromeRule(it) },
            budgetRules = emptyList(),
            summonRules = listOf(SummonRule(kind = shelters)),
        )
    }
}
