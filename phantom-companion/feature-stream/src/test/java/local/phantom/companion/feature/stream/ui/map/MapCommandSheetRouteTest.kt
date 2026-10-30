package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.core.nav.RoutePlanner
import local.phantom.companion.core.net.map.BarrierSighting
import local.phantom.companion.core.net.map.RouteIntelReport
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import local.phantom.companion.feature.stream.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MapCommandSheetRouteTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-net").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with core-net/ not found") as Nothing
    }

    @Test
    fun `mode minutes are computed for all four profiles and MapCommandSheet has no uk-UA locale or Cyrillic`() {
        val minutes = estimateModeMinutes(750.0, RouteProfile.FOOT, 540.0)
        assertEquals(
            setOf(RouteProfile.FOOT, RouteProfile.TRANSIT, RouteProfile.BIKE, RouteProfile.CAR),
            minutes.keys,
        )
        assertTrue(minutes.values.all { it > 0 })
        assertTrue(minutes.getValue(RouteProfile.BIKE) < minutes.getValue(RouteProfile.FOOT))
        assertNotEquals(profileLabelRes(RouteProfile.BIKE), profileLabelRes(RouteProfile.FOOT))

        val barrierOption = RoutePlanner.Option(
            variant = RouteVariant(
                points = listOf(50.0 to 30.0, 50.1 to 30.1),
                distanceMeters = 20_000.0,
                durationSeconds = 1800.0,
                steps = emptyList(),
                speedsMps = emptyList(),
                profile = RouteProfile.CAR,
            ),
            intel = RoutePlanner.Intel.Ready(
                RouteIntelReport(
                    classShares = emptyList(),
                    surfaceShares = emptyList(),
                    litFraction = null,
                    maxspeedMax = null,
                    coverage = 1.0,
                    cameras = emptyList(),
                    barriers = listOf(BarrierSighting(50.4, 30.5, "checkpoint", null)),
                    elevations = null,
                    ascentMeters = null,
                    descentMeters = null,
                ),
            ),
        )
        val info = MapViewModel.RouteInfo(
            distanceMeters = 20_000.0,
            durationSeconds = 1800.0,
            profile = RouteProfile.CAR,
            destLat = 50.1,
            destLon = 30.1,
            destName = "Test",
        )
        val barrierCaution = routeCautions(info, barrierOption, null, null)
            .filterIsInstance<RouteCaution.Barrier>()
            .firstOrNull()
        assertNotNull(barrierCaution)
        assertEquals(R.string.map_route_barriers_stops, barrierCaution!!.labelRes)
        assertTrue(barrierCaution.description.contains("КПП / блокпости"))

        val file = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapCommandSheet.kt",
        )
        assertTrue("missing ${file.path}", file.isFile)
        val raw = file.readText()

        assertFalse(
            "MapCommandSheet.kt still hardcodes uk-UA locale",
            raw.contains("""Locale("uk", "UA")"""),
        )
        assertTrue(
            "MapCommandSheet.kt must use LocalConfiguration locale for clock formatting",
            raw.contains("LocalConfiguration.current.locales[0]"),
        )
        assertTrue(
            "MapCommandSheet.kt must render TransitRouteLegCard",
            raw.contains("TransitRouteLegCard("),
        )
        assertTrue(
            "MapCommandSheet.kt must reference peer_share_reach_nearby",
            raw.contains("R.string.peer_share_reach_nearby"),
        )

        val withoutBlockComments = raw.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        val withoutComments = withoutBlockComments.replace(Regex("""//[^\n]*"""), "")
        val withoutBarrierMatching = withoutComments
            .replace("""n.contains("шлагбаум")""", "")
            .replace("""n.contains("блокпост")""", "")

        val cyrillicLiterals = Regex(""""([^"\\]|\\.)*"""")
            .findAll(withoutBarrierMatching)
            .map { it.value }
            .filter { Regex("""[\p{IsCyrillic}]""").containsMatchIn(it) }
            .toList()

        assertEquals(
            "MapCommandSheet.kt contains hardcoded Cyrillic UI literals: $cyrillicLiterals",
            emptyList<String>(),
            cyrillicLiterals,
        )
    }
}
