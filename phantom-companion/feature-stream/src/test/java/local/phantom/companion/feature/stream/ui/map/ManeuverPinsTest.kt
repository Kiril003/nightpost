package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.net.map.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManeuverPinsTest {

    private fun step(
        type: String,
        modifier: String? = null,
        angle: Float? = null,
        bearingAfter: Float? = null,
        exit: Int? = null,
        lat: Double = 50.45,
        lon: Double = 30.52,
    ) = RouteStep(
        type = type, modifier = modifier, roadName = "вул.", distanceMeters = 100.0,
        durationSeconds = 30.0, lat = lat, lon = lon, exitNumber = exit, turnAngleDeg = angle,
        bearingAfterDeg = bearingAfter,
    )

    /** As OSRM sends it: two absolute bearings, the delta derived from them
     *  (OsrmClient.turnAngleOf is internal to core-net, so the wrap is
     *  repeated here rather than reached for). */
    private fun turn(bearingBefore: Double, bearingAfter: Double): RouteStep {
        var d = bearingAfter - bearingBefore
        while (d > 180.0) d -= 360.0
        while (d <= -180.0) d += 360.0
        return step("turn", "right", angle = d.toFloat(), bearingAfter = bearingAfter.toFloat())
    }

    @Test
    fun `starting and arriving are not decisions`() {
        val pins = ManeuverPins.of(listOf(step("depart"), step("arrive"), step("new name")))
        assertTrue("a pin at the start point is noise on the line", pins.isEmpty())
    }

    @Test
    fun `one turn carries both datums, and they are different numbers`() {
        val pins = ManeuverPins.of(listOf(turn(200.0, 262.0)))
        assertEquals(1, pins.size)
        assertEquals("relative turn, banner frame", 62f, pins[0].rotationDeg, 0.01f)
        assertEquals("absolute bearing, map frame", 262f, pins[0].bearingDeg!!, 0.01f)
    }

    @Test
    fun `heading south then west, the map symbol points west`() {
        // The datum the layer feeds icon-rotate under ICON_ROTATION_ALIGNMENT_MAP
        // is a compass bearing. This fixture is the whole bug in one line: the
        // turn is 90°, the road runs 270°.
        val pins = ManeuverPins.of(listOf(turn(180.0, 270.0)))
        assertEquals(
            "old datum would have drawn this 180° wrong",
            270f, pins[0].bearingDeg!!, 0.01f,
        )
        assertEquals("the turn itself is still 90° right", 90f, pins[0].rotationDeg, 0.01f)
    }

    @Test
    fun `without bearings the modifier places a marker that points nowhere`() {
        val pins = ManeuverPins.of(listOf(step("turn", "left")))
        assertEquals("bucket angle still chooses the glyph", -90f, pins[0].rotationDeg, 0.01f)
        assertNull(
            "no bearing from the router means the layer must fall back to the non-directional ring",
            pins[0].bearingDeg,
        )
    }

    @Test
    fun `a bend too gentle to be a decision gets no marker`() {
        // The line already curves; a pin here is clutter on a motorway.
        assertTrue(ManeuverPins.of(listOf(step("continue", "straight", angle = 6f))).isEmpty())
    }

    @Test
    fun `a roundabout is always marked, however small its angle`() {
        val pins = ManeuverPins.of(listOf(step("roundabout", "straight", angle = 2f, exit = 2)))
        assertEquals(1, pins.size)
        assertTrue(pins[0].roundabout)
        assertEquals(2, pins[0].exitNumber)
    }

    @Test
    fun `a step with no location is dropped rather than pinned to Null Island`() {
        assertTrue(ManeuverPins.of(listOf(step("turn", "right", lat = 0.0, lon = 0.0))).isEmpty())
    }

    @Test
    fun `у тісноті лишається різкий поворот, а не плавний`() {
        // Маркери тепер розріджуються, і черга вирішує, хто вціліє. Плавний
        // зʼїзд лінія маршруту показує сама; різкий поворот — ні.
        val sharp = ManeuverPins.of(listOf(step("turn", "right", angle = 95f)))[0]
        val gentle = ManeuverPins.of(listOf(step("turn", "slight right", angle = 22f)))[0]
        assertTrue(
            "різкий (${sharp.sortKey}) мусить іти перед плавним (${gentle.sortKey})",
            sharp.sortKey < gentle.sortKey,
        )
    }

    @Test
    fun `кільце не поступається нікому`() {
        val ring = ManeuverPins.of(listOf(step("roundabout", "straight", angle = 2f, exit = 2)))[0]
        val uturn = ManeuverPins.of(listOf(step("turn", "uturn", angle = 180f)))[0]
        assertEquals("кільце — перше в черзі", 0f, ring.sortKey, 0.001f)
        assertTrue("навіть розворот не витісняє кільце", ring.sortKey <= uturn.sortKey)
    }

    @Test
    fun `the geojson carries what the layer reads`() {
        val json = buildManeuverGeoJson(
            listOf(
                ManeuverPins.Pin(
                    50.45, 30.52, rotationDeg = 90f, bearingDeg = 262f,
                    roundabout = false, exitNumber = null,
                ),
            ),
        ).replace(" ", "")
        assertTrue("icon-rotate reads the map-frame bearing", "\"bearing\":262" in json)
        assertTrue("the banner-frame turn stays available", "\"rotate\":90" in json)
        // Без цього поля шар розкладає маркери в порядку джерела, тобто в
        // тісноті гине не найменш важливий, а той, що трапився пізніше.
        assertTrue("\"sort\":" in json)
        assertTrue("\"roundabout\":false" in json)
        assertTrue("coordinates are lon,lat", "[30.52,50.45]" in json)
    }

    @Test
    fun `a pin with no bearing writes no bearing at all`() {
        val json = buildManeuverGeoJson(
            listOf(ManeuverPins.Pin(50.45, 30.52, rotationDeg = 90f, roundabout = false, exitNumber = null)),
        ).replace(" ", "")
        assertTrue(
            "an absent property is what selects the non-directional ring; 0 would point north",
            "\"bearing\"" !in json,
        )
    }

    @Test
    fun `номер кроку рахується від повного плану, а не від списку міток`() {
        // Сесія супроводу рахує кроки всі підряд, разом зі старом і зміною
        // назви дороги. Якби мітка несла свій порядковий номер, підсвітився б
        // чужий поворот — і тим упевненіше, чим довший маршрут.
        val pins = ManeuverPins.of(
            listOf(
                step("depart"),
                step("new name"),
                step("turn", "right", angle = 80f),
            ),
        )
        assertEquals(1, pins.size)
        assertEquals("третій крок плану, а не перша мітка", 2, pins[0].stepIndex)
    }

    @Test
    fun `наступний маневр позначено і поставлено першим у черзі`() {
        val pins = ManeuverPins.of(
            listOf(step("turn", "right", angle = 80f), step("turn", "left", angle = 80f)),
        )
        val json = buildManeuverGeoJson(pins, activeStep = 1).replace(" ", "")
        assertEquals("рівно один активний", 1, Regex("\"active\":true").findAll(json).count())
        assertTrue("активний іде поперед усіх", "\"sort\":-1" in json)
    }

    @Test
    fun `поза супроводом жоден маркер не вдає наступного`() {
        val pins = ManeuverPins.of(listOf(step("turn", "right", angle = 80f)))
        val json = buildManeuverGeoJson(pins).replace(" ", "")
        assertTrue("без супроводу підсвічувати нема чого", "\"active\":true" !in json)
    }

    @Test
    fun `an empty plan produces an empty collection, not broken json`() {
        val json = buildManeuverGeoJson(emptyList())
        assertTrue(json.startsWith("{") && json.endsWith("}"))
        assertTrue("\"features\":[]" in json.replace(" ", ""))
    }
}
