package local.phantom.companion.feature.stream.ui.map

import androidx.test.core.app.ApplicationProvider
import java.io.File
import local.phantom.companion.feature.stream.ui.map.dossier.DossierObjectFacts
import local.phantom.companion.feature.stream.ui.map.dossier.DossierTarget
import local.phantom.companion.feature.stream.ui.map.dossier.PlaceDossier
import local.phantom.companion.feature.stream.ui.map.dossier.entityRefOf
import local.phantom.companion.feature.stream.ui.map.icons.glyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Перепис мапи 24.09 (docs/map-census.md): театр, знятий із мапи, не повертається. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class MapCensusSubtractionTest {

    @Before
    fun setUp() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    // ── Досʼє: слово дорівнює тегу ──────────────────────────────────────────

    @Test
    fun `fuel tags say what is sold, never what is in stock`() {
        val tags = mapOf("amenity" to "fuel", "fuel:octane_95" to "yes", "fuel:diesel" to "yes", "shop" to "convenience")
        assertEquals(listOf("А-95", "дизель"), DossierObjectFacts.fuelKinds(tags))
        val services = DossierObjectFacts.stationServices(tags)
        assertTrue("магазин став мережевою кавʼярнею: $services", services.none { it.contains("WOG") || it.contains("Hot-Dog") })
        assertTrue(services.contains("магазин"))
        assertFalse("«в наявності» повернулось", code("ui/map/dossier/DossierObjectCards.kt").contains("НАЯВНОСТІ"))
    }

    @Test
    fun `wifi is wifi, not starlink`() {
        val shelter = DossierObjectFacts.shelter(mapOf("internet_access" to "wlan"))
        assertEquals(listOf("Wi-Fi"), shelter)
    }

    @Test
    fun `a tag is not inflated into a programme, a panorama or a playroom`() {
        assertEquals(listOf("відпуск за рецептом"), DossierObjectFacts.medical(mapOf("dispensing" to "yes")))
        assertEquals(listOf("ліфт"), DossierObjectFacts.mall(mapOf("elevator" to "yes")))
        assertEquals(listOf("дитячий стілець"), DossierObjectFacts.dining(mapOf("highchair" to "yes")))
        assertTrue(DossierObjectFacts.dining(mapOf("child_friendly" to "yes")).isEmpty())
        assertEquals(listOf("критий"), DossierObjectFacts.parking(mapOf("covered" to "yes")))
    }

    @Test
    fun `place chips carry words, not emoji`() {
        val tags = mapOf(
            "amenity" to "fuel", "fuel:octane_95" to "yes", "generator:source" to "diesel",
            "emergency" to "defibrillator", "access" to "private", "drive_through" to "yes",
        )
        val chips = dossier(tags).quickChips()
        assertTrue(chips.isNotEmpty())
        val withEmoji = chips.filter { hasEmoji(it) }
        assertEquals("емодзі в пігулках досʼє", emptyList<String>(), withEmoji)
        assertTrue("латина «drive-through» повернулась", chips.none { it.contains("drive", ignoreCase = true) })
    }

    // ── Небо ────────────────────────────────────────────────────────────────

    @Test
    fun `every alert wears one neutral glyph, in the sheet and on the map`() {
        assertEquals(setOf(ThreatType.UNKNOWN.glyph()), ThreatType.values().map { it.meta.glyph }.toSet())
        assertTrue("значок на мапі має брати знак з meta, як аркуш", code("ui/map/MapAlertLayers.kt").contains("type.meta.glyph,"))
    }

    @Test
    fun `nobody claims to be refining the threat type`() {
        assertFalse(code("ui/map/ThreatBriefSheet.kt").contains("уточнюється"))
    }

    // ── Транспорт: інтервал моделі не видається за інтервал лінії ───────────

    @Test
    fun `the line card does not print a modelled headway as the line's own`() {
        val tabs = code("ui/map/transit/TransitHubTabs.kt")
        assertFalse("інтервал моделі знову поданий як факт лінії", tabs.contains("Інтервал ~"))
        assertFalse("пік/день/вечір моделі знову на картці лінії", tabs.contains("line.headway.peak"))
        val card = code("ui/map/dossier/DossierTransitCard.kt")
        assertFalse("інтервал без слова «модель»", card.contains("\"Інтервал ~"))
    }

    // ── Кабіна ──────────────────────────────────────────────────────────────

    @Test
    fun `the cockpit keeps no parking or trip record it cannot keep`() {
        val cockpitDir = File("src/main/java/local/phantom/companion/feature/stream/ui/map/cockpit")
        assertFalse(File(cockpitDir, "ParkingRecord.kt").exists())
        assertFalse(File(cockpitDir, "TripComputer.kt").exists())
        val sheet = code("ui/map/cockpit/CarCockpitSheet.kt")
        listOf("чорну скриньку", "Паркування", "ЧАС У РУСІ", "HUD", "WOG").forEach {
            assertFalse("у кабіні знову «$it»", sheet.contains(it))
        }
        val state = code("ui/map/MapSceneState.kt")
        assertFalse(state.contains("parkingRecord"))
        assertFalse(state.contains("tripStats"))
        listOf("values-uk", "values").forEach {
            assertFalse(hasEmoji(File("src/main/res/$it/strings_cockpit.xml").readText()))
        }
    }

    // ── Каталог «поруч» ─────────────────────────────────────────────────────

    @Test
    fun `discovery notes promise nothing the tag does not say`() {
        listOf("src/main/res/values-uk/strings_discovery.xml", "src/main/res/values/strings_discovery.xml").forEach { path ->
            val text = File(path).readText()
            listOf("Starlink", "EcoFlow", "Сертифік", "Certified", "Перевірені", "Verified", "фільтровент", "filtered").forEach {
                assertFalse("$path: «$it» повернулось", text.contains(it, ignoreCase = true))
            }
        }
    }

    // ── Емодзі в коді мапи ──────────────────────────────────────────────────

    @Test
    fun `map code carries no emoji outside comments`() {
        val root = File("src/main/java/local/phantom/companion/feature/stream/ui/map")
        assertTrue(root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filterNot { (_, l) -> val t = l.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
                    .filter { (_, l) -> hasEmoji(l.substringBefore("//")) }
                    .map { (i, _) -> "${f.name}:${i + 1}" }
            }
            .toList()
        assertEquals("емодзі в коді мапи", emptyList<String>(), offenders)
    }

    private fun hasEmoji(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp == 0x2B50 || cp == 0x2B55 || cp == 0xFE0F) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun code(rel: String): String {
        val f = File("src/main/java/local/phantom/companion/feature/stream/$rel")
        assertTrue("не знайдено: $rel", f.isFile)
        return f.readLines()
            .filterNot { val t = it.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
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
