package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The complaint these encode: «маршрут і досі тупий, через паркан і
 * прибудинкову територію веде мене».
 */
class RoadProfileTest {

    private val street = mapOf("highway" to "residential")
    private val courtyard = mapOf("highway" to "service", "service" to "driveway")

    @Test
    fun `private land is not a route`() {
        assertTrue(RoadProfile.blocked(mapOf("highway" to "service", "access" to "private"), foot = true))
        assertTrue(RoadProfile.blocked(mapOf("highway" to "track", "access" to "no"), foot = false))
    }

    @Test
    fun `a public footpath across private land stays open`() {
        // OSM says this exactly: the road is private, the right of way is not.
        val tags = mapOf("highway" to "service", "access" to "private", "foot" to "yes")
        assertFalse(RoadProfile.blocked(tags, foot = true))
        assertTrue(RoadProfile.blocked(tags, foot = false))
    }

    @Test
    fun `nobody walks a motorway and nobody drives a staircase`() {
        assertTrue(RoadProfile.blocked(mapOf("highway" to "motorway"), foot = true))
        assertFalse(RoadProfile.blocked(mapOf("highway" to "motorway"), foot = false))
        assertTrue(RoadProfile.blocked(mapOf("highway" to "steps"), foot = false))
        assertFalse(RoadProfile.blocked(mapOf("highway" to "steps"), foot = true))
    }

    @Test
    fun `a courtyard costs several times a street`() {
        val street = RoadProfile.costFactor(street, foot = true)
        val yard = RoadProfile.costFactor(courtyard, foot = true)
        assertEquals(1.0, street, 1e-9)
        assertTrue("courtyard must cost far more than a street", yard > street * 3)
    }

    @Test
    fun `a courtyard is discouraged, never forbidden — a man may live there`() {
        assertFalse(RoadProfile.blocked(courtyard, foot = true))
    }

    @Test
    fun `unmade ground slows a walk but not to a stop`() {
        val rough = RoadProfile.costFactor(
            mapOf("highway" to "path", "surface" to "mud"), foot = true,
        )
        assertTrue(rough > 1.0)
        assertTrue(rough < 2.0)
    }

    @Test
    fun `access destination is passable but never a through route`() {
        assertTrue(RoadProfile.costFactor(mapOf("highway" to "residential", "access" to "destination"), foot = false) > 2.0)
    }

    @Test
    fun `a road with no tags at all costs exactly its length`() {
        assertEquals(1.0, RoadProfile.costFactor(emptyMap(), foot = true), 1e-9)
        assertFalse(RoadProfile.blocked(emptyMap(), foot = true))
    }

    @Test
    fun `speed follows the road class instead of one flat guess`() {
        val primary = RoadProfile.speedMps(mapOf("highway" to "primary"), foot = false)
        val yard = RoadProfile.speedMps(courtyard, foot = false)
        assertTrue(primary > yard)
    }

    @Test
    fun `a posted limit is read, and a nonsense one is ignored`() {
        val posted = RoadProfile.speedMps(mapOf("highway" to "primary", "maxspeed" to "50"), foot = false)
        assertEquals(50 * 1000.0 / 3600.0 * 0.7, posted, 1e-6)
        val junk = RoadProfile.speedMps(mapOf("highway" to "primary", "maxspeed" to "walk"), foot = false)
        assertEquals(13.0, junk, 1e-9)
    }

    @Test
    fun `stairs are slower on foot than pavement`() {
        assertTrue(
            RoadProfile.speedMps(mapOf("highway" to "steps"), foot = true) <
                RoadProfile.speedMps(street, foot = true),
        )
    }

    @Test
    fun `a sign is a sign, a class is a class, and nothing is an assumption`() {
        assertEquals(
            RoadProfile.SpeedBasis.POSTED,
            RoadProfile.speedBasis(mapOf("highway" to "primary", "maxspeed" to "50"), foot = false),
        )
        assertEquals(
            RoadProfile.SpeedBasis.CLASS,
            RoadProfile.speedBasis(mapOf("highway" to "primary"), foot = false),
        )
        // A limit OSM wrote as prose is not a sign this router can read; it
        // must fall back to the class and SAY so, not claim a reading.
        assertEquals(
            RoadProfile.SpeedBasis.CLASS,
            RoadProfile.speedBasis(mapOf("highway" to "primary", "maxspeed" to "walk"), foot = false),
        )
        assertEquals(
            RoadProfile.SpeedBasis.ASSUMED,
            RoadProfile.speedBasis(emptyMap(), foot = false),
        )
    }

    @Test
    fun `a class with no pace of its own is still an assumption`() {
        // `track` is a known class the table has no driving pace for, so the
        // number really is the flat guess — and must not dress as a class.
        assertEquals(
            RoadProfile.SpeedBasis.ASSUMED,
            RoadProfile.speedBasis(mapOf("highway" to "track"), foot = false),
        )
    }

    @Test
    fun `walking pace is an assumption unless the ground changes it`() {
        assertEquals(
            RoadProfile.SpeedBasis.ASSUMED,
            RoadProfile.speedBasis(mapOf("highway" to "residential", "maxspeed" to "50"), foot = true),
        )
        assertEquals(
            RoadProfile.SpeedBasis.CLASS,
            RoadProfile.speedBasis(mapOf("highway" to "steps"), foot = true),
        )
    }

    private fun drive(vararg tags: Pair<String, String>) =
        RoadProfile.costFactor(mapOf(*tags), foot = false)

    @Test
    fun `trunk is the national network, not a lesser motorway`() {
        // Ukraine tags 33 ways `motorway` and 16 936 `trunk`. Whatever the top
        // tier is worth, `trunk` must be in it.
        assertEquals(1.0, drive("highway" to "trunk"), 1e-9)
        assertEquals(drive("highway" to "motorway"), drive("highway" to "trunk"), 1e-9)
        assertTrue(
            "a national road must not cost more than a regional one",
            drive("highway" to "trunk") < drive("highway" to "secondary"),
        )
    }

    @Test
    fun `the driving ladder falls monotonically from the trunk to the street`() {
        val rungs = listOf(
            drive("highway" to "trunk"),
            drive("highway" to "primary"),
            drive("highway" to "secondary"),
            drive("highway" to "tertiary"),
            drive("highway" to "residential"),
        )
        for (i in 1 until rungs.size) {
            assertTrue("rung $i (${rungs[i]}) must cost more than rung ${i - 1} (${rungs[i - 1]})", rungs[i] > rungs[i - 1])
        }
        // The tolerance has a ceiling: a router that costs distance must never
        // detour as far as one costing time would, because below trunk the
        // class is a guess on 94% of Ukraine's ways.
        assertTrue("the ladder must stay under the speed table's 3.4×", rungs.last() <= 2.0)
    }

    @Test
    fun `a slip road rides with the road it serves`() {
        // Charging the ramp would make the router dodge the only way onto the
        // trunk it just decided to prefer.
        assertEquals(drive("highway" to "trunk"), drive("highway" to "trunk_link"), 1e-9)
        assertEquals(drive("highway" to "primary"), drive("highway" to "primary_link"), 1e-9)
    }

    @Test
    fun `walking is never pushed onto a bigger road`() {
        // The ladder is a driving preference. A pedestrian gains nothing from a
        // national road and loses a pavement.
        assertEquals(
            RoadProfile.costFactor(mapOf("highway" to "residential"), foot = true),
            RoadProfile.costFactor(mapOf("highway" to "secondary"), foot = true),
            1e-9,
        )
        assertEquals(1.0, RoadProfile.costFactor(mapOf("highway" to "residential"), foot = true), 1e-9)
    }

    @Test
    fun `a class the table does not know is local, an absent one is not`() {
        // The extract really does carry `highway=Приватне Підприємство…` and
        // `highway=yes`. We know how an М-road is tagged; that is not it.
        // Equality with `residential` alone would not prove this — both fall
        // through the same branch, so it holds however that branch answers.
        assertTrue(
            "an unknown class must land on the bottom rung, not the top",
            drive("highway" to "yes") > drive("highway" to "tertiary"),
        )
        assertEquals(drive("highway" to "residential"), drive("highway" to "yes"), 1e-9)
        assertEquals(drive("highway" to "residential"), drive("highway" to "road"), 1e-9)
        // But a way with no class at all is the legacy mesh row, and it was
        // promised it would route exactly as before.
        assertEquals(1.0, RoadProfile.costFactor(emptyMap(), foot = false), 1e-9)
    }

    @Test
    fun `a living street never becomes cheaper than the street beside it`() {
        assertTrue(
            "living_street ${drive("highway" to "living_street")} must cost more than " +
                "residential ${drive("highway" to "residential")}",
            drive("highway" to "living_street") > drive("highway" to "residential"),
        )
    }

    @Test
    fun `the courtyard keeps its whole margin over an ordinary street`() {
        // The ladder lifts the street; if it did not lift the driveway with it,
        // «маршрут через прибудинкову територію» would come halfway back.
        val yard = drive("highway" to "service", "service" to "driveway")
        val street = drive("highway" to "residential")
        assertEquals(3.5, yard / street, 1e-9)
    }

    @Test
    fun `a posted limit beats the class on the very road the class is about`() {
        // Південний міст, way 4335942, is `highway=trunk maxspeed=50` in the
        // real bake. Preferring trunk must not smuggle 90 km/h onto it.
        val urban = mapOf("highway" to "trunk", "maxspeed" to "50")
        assertEquals(RoadProfile.SpeedBasis.POSTED, RoadProfile.speedBasis(urban, foot = false))
        assertEquals(50 * 1000.0 / 3600.0 * 0.7, RoadProfile.speedMps(urban, foot = false), 1e-9)
        assertTrue(
            "the sign must win over the class, even downwards",
            RoadProfile.speedMps(urban, foot = false) <
                RoadProfile.speedMps(mapOf("highway" to "trunk"), foot = false),
        )
        // And the preference is unmoved by it: cost and speed are two claims.
        assertEquals(drive("highway" to "trunk"), drive("highway" to "trunk", "maxspeed" to "50"), 1e-9)
    }

    @Test
    fun `a ramp is not timed at the speed of the road it joins`() {
        assertTrue(
            RoadProfile.speedMps(mapOf("highway" to "trunk_link"), foot = false) <
                RoadProfile.speedMps(mapOf("highway" to "trunk"), foot = false),
        )
        assertEquals(
            RoadProfile.SpeedBasis.CLASS,
            RoadProfile.speedBasis(mapOf("highway" to "trunk_link"), foot = false),
        )
    }

    private fun walk(vararg tags: Pair<String, String>) =
        RoadProfile.costFactor(mapOf(*tags), foot = true)

    @Test
    fun `a man may walk the national network, and pays for it`() {
        // 16 936 `trunk` ways are the ordinary Н- and М-roads. Blocking them
        // bought a 250× detour over unmapped verges, in a country whose OSM
        // holds no mine data at all.
        assertFalse(RoadProfile.blocked(mapOf("highway" to "trunk"), foot = true))
        assertFalse(RoadProfile.blocked(mapOf("highway" to "trunk_link"), foot = true))

        val trunk = walk("highway" to "trunk")
        // Floor: dearer than the dearest yard a walker may legally be sent
        // down, or the router offers a highway in preference to a driveway.
        val worstYard = walk(
            "highway" to "service", "service" to "parking_aisle", "surface" to "gravel",
        )
        assertTrue("trunk $trunk must cost more than the worst yard $worstYard", trunk > worstYard)
        // Ceiling: cheaper than a restricted courtyard, past which the router
        // starts threading yards together to dodge asphalt again.
        val restrictedYard = walk(
            "highway" to "service", "service" to "driveway", "access" to "destination",
        )
        assertTrue("trunk $trunk must stay under the yard ladder $restrictedYard", trunk < restrictedYard)
    }

    @Test
    fun `автомагістраль stays shut on foot, and so does a motorroad sign`() {
        assertTrue(RoadProfile.blocked(mapOf("highway" to "motorway"), foot = true))
        assertTrue(RoadProfile.blocked(mapOf("highway" to "motorway_link"), foot = true))
        // The 0.18% of trunk ways that really are for cars only.
        assertTrue(RoadProfile.blocked(mapOf("highway" to "trunk", "motorroad" to "yes"), foot = true))
        assertTrue(RoadProfile.blocked(mapOf("highway" to "trunk_link", "motorroad" to "yes"), foot = true))
        // And the 2.2% OSM says outright.
        assertTrue(RoadProfile.blocked(mapOf("highway" to "trunk", "foot" to "no"), foot = true))
        // None of which is anything to a driver.
        assertFalse(RoadProfile.blocked(mapOf("highway" to "trunk", "motorroad" to "yes"), foot = false))
    }

    @Test
    fun `a trunk with a mapped pavement is an ordinary street to walk`() {
        val bare = walk("highway" to "trunk")
        val paved = walk("highway" to "trunk", "sidewalk" to "both")
        assertTrue("a mapped pavement $paved must cost far less than a shoulder $bare", paved < bare)
        assertEquals(1.0, paved, 1e-9)

        // The same line the operator-facing notice is drawn on: we know a
        // pavement is there only when OSM says so, and «no sidewalk tag» is
        // not «there is a shoulder».
        assertTrue(RoadProfile.isTrunkShoulder(mapOf("highway" to "trunk")))
        assertTrue(RoadProfile.isTrunkShoulder(mapOf("highway" to "trunk", "sidewalk" to "no")))
        assertFalse(RoadProfile.isTrunkShoulder(mapOf("highway" to "trunk", "sidewalk" to "separate")))
        assertFalse(RoadProfile.isTrunkShoulder(mapOf("highway" to "residential")))
    }

    @Test
    fun `ASSUMED always means exactly the old flat speed`() {
        // The promise the whole migration rests on: a way we know nothing about
        // is timed today exactly as it was timed before tags existed.
        val cases = listOf<Map<String, String>>(
            emptyMap(),
            mapOf("highway" to "track"),
            mapOf("surface" to "asphalt"),
            mapOf("maxspeed" to "walk"),
        )
        for (tags in cases) {
            for (foot in listOf(true, false)) {
                val flat = if (foot) LocalRouter.FOOT_SPEED_MPS else LocalRouter.CAR_SPEED_MPS
                assertEquals(
                    "$tags foot=$foot",
                    RoadProfile.SpeedBasis.ASSUMED,
                    RoadProfile.speedBasis(tags, foot),
                )
                assertEquals("$tags foot=$foot", flat, RoadProfile.speedMps(tags, foot), 1e-12)
            }
        }
    }
}
