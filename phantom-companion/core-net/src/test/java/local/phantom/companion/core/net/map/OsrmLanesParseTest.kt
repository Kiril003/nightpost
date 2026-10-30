package local.phantom.companion.core.net.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRESENCE — the lane half of an OSRM answer.
 *
 * The body below is shaped like a real `steps=true` response at a junction
 * where `turn:lanes=left|through|through;right` is tagged: four lanes on the
 * approach, the router's own `valid` verdict on each, and the intersections
 * further along the step that must NOT be mistaken for the maneuver's own.
 */
class OsrmLanesParseTest {

    private val body = """
        {"code":"Ok","routes":[
          {"distance":1200.0,"duration":180.0,
           "geometry":{"coordinates":[[30.50,50.40],[30.51,50.41],[30.52,50.42]]},
           "legs":[{"steps":[
             {"distance":600.0,"duration":90.0,"name":"проспект Науки",
              "maneuver":{"type":"depart","location":[30.50,50.40],"bearing_after":45},
              "intersections":[{"location":[30.50,50.40]}]},
             {"distance":400.0,"duration":60.0,"name":"вулиця Васильківська",
              "maneuver":{"type":"turn","modifier":"right","location":[30.51,50.41],
                          "bearing_before":45,"bearing_after":135},
              "intersections":[
                {"location":[30.51,50.41],"lanes":[
                  {"valid":false,"indications":["left"]},
                  {"valid":false,"indications":["straight"]},
                  {"valid":true,"indications":["straight","right"]},
                  {"valid":true,"indications":["right"]}]},
                {"location":[30.515,50.415],"lanes":[
                  {"valid":true,"indications":["none"]}]}]},
             {"distance":0.0,"duration":0.0,"name":"",
              "maneuver":{"type":"arrive","location":[30.52,50.42]},
              "intersections":[{"location":[30.52,50.42]}]}]}]}
        ]}
    """.trimIndent()

    private fun steps() = parseRouteVariants(body, RouteProfile.CAR)!!.first().steps

    @Test
    fun `lanes come off the step's own first intersection`() {
        val turn = steps()[1]
        assertEquals(4, turn.lanes.size)
        assertEquals(listOf("left"), turn.lanes[0].indications)
        assertEquals(listOf("straight", "right"), turn.lanes[2].indications)
    }

    @Test
    fun `valid is the router's verdict, not our guess`() {
        val turn = steps()[1]
        assertEquals(listOf(false, false, true, true), turn.lanes.map { it.validForManeuver })
    }

    @Test
    fun `an unmarked lane carries no indication rather than the word none`() {
        // OSRM writes "none" where the asphalt has no arrow painted on it;
        // carrying that word forward would make the strip draw a phantom arrow.
        val lane = parseRouteVariants(body, RouteProfile.CAR)!!
            .first().steps[1].lanes.let { it }
        assertTrue(lane.none { "none" in it.indications })
    }

    @Test
    fun `steps without lane tagging stay honestly empty`() {
        val untagged = steps().filterIndexed { i, _ -> i != 1 }
        assertTrue(untagged.all { it.lanes.isEmpty() })
    }

    @Test
    fun `a response with no intersections at all still parses`() {
        val bare = """{"code":"Ok","routes":[{"distance":10.0,"duration":5.0,
            "geometry":{"coordinates":[[30.5,50.4],[30.51,50.41]]},
            "legs":[{"steps":[{"distance":10.0,"duration":5.0,"name":"",
              "maneuver":{"type":"depart","location":[30.5,50.4]}}]}]}]}"""
        val steps = parseRouteVariants(bare, RouteProfile.CAR)!!.first().steps
        assertEquals(1, steps.size)
        assertTrue(steps.first().lanes.isEmpty())
    }
}
