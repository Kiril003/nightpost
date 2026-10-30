package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.core.data.spatial.isRefusedMarkKind
import local.phantom.companion.core.net.live.LiveMarksConfig
import local.phantom.companion.feature.stream.ui.map.cockpit.COCKPIT_MARKS
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget
import local.phantom.companion.feature.stream.ui.map.dossier.PlaceDossier
import local.phantom.companion.feature.stream.ui.map.dossier.entityRefOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Межі безпеки мапи: жодного краудсорсу позицій, жодного військового обʼєкта на
 * картці, жодної підсвітки військової землі. Кожен тест тут червоніє, щойно
 * щось із цього повертається.
 */
class MapSafetyBoundaryTest {

    @Test
    fun `every mark a finger can place is in the closed shared vocabulary`() {
        val sheet = SHEET_MARKS.map { it.kind }
        assertTrue("аркуш точки ставить поза словником: $sheet", LiveMarksConfig.ALLOWED_KINDS.containsAll(sheet))
        val refused = (sheet + COCKPIT_MARKS.map { it.kind }).filter(::isRefusedMarkKind)
        assertEquals("рука ставить заборонений вид", emptyList<String>(), refused)
    }

    @Test
    fun `a military tag adds nothing to the place card`() {
        val base = mapOf("amenity" to "fuel", "name" to "Заправка", "fuel:diesel" to "yes")
        val clean = dossier(base)
        listOf(
            "military" to "checkpoint", "military" to "barracks", "military" to "bunker",
            "military" to "trench", "landuse" to "military", "access" to "military",
            "highway" to "checkpoint",
        ).forEach { (k, v) ->
            val tagged = dossier(base + (k to v))
            assertEquals("$k=$v змінив пігулки", clean.quickChips(), tagged.quickChips())
            assertEquals("$k=$v змінив факти", clean.extraFacts(), tagged.extraFacts())
            assertEquals("$k=$v змінив те, що бачить PHANTOM", clean.digest(), tagged.digest())
        }
    }

    @Test
    fun `military land is painted like any other land`() {
        val styles = File("src/main/assets/styles").listFiles { f -> f.extension == "json" }!!.toList()
        assertTrue(styles.isNotEmpty())
        val lit = styles.filter { Regex("\"military\"").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("випечений стиль знову виділяє військову землю", emptyList<String>(), lit)
        listOf("MapGroundBackfill.kt", "MapGroundCartography.kt").forEach {
            val src = File("src/main/java/local/phantom/companion/feature/stream/ui/map/$it").readText()
            assertTrue("$it знову фарбує військову землю", "\"military\"" !in src)
        }
    }

    private fun dossier(tags: Map<String, String>): PlaceDossier {
        val entity = entityRefOf(tags = tags)
        return PlaceDossier(
            target = DossierTarget(lat = 50.45, lon = 30.52),
            title = entity.name ?: "?",
            kindLabel = null,
            category = entity.category,
            entity = entity,
            address = null,
            tags = tags,
            openStatus = null,
            rawHours = null,
            building = null,
            reference = null,
            transitLines = emptyList(),
            similar = emptyList(),
        )
    }
}
