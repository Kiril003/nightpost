package local.phantom.companion.feature.stream.ui.map.dossier

import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.feature.stream.ui.map.MapText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Вирок власника 15.09: картка друкувала сирі значення OSM («permissive»,
 * «basement», «lift_gate», «Q12345») замість українських слів або чесної
 * тиші. Кожен тест тут — одне таке значення, знайдене живим Overpass по
 * Остраві (49.78,18.10,49.88,18.30) того ж дня.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class PlaceDossierExtraFactsTest {

    @Before
    fun setUp() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    private fun dossier(tags: Map<String, String>): PlaceDossier {
        val target = DossierTarget(lat = 49.83, lon = 18.29)
        val entity = entityRefOf(tags = tags)
        return PlaceDossier(
            target = target,
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

    private fun facts(tags: Map<String, String>) = dossier(tags).extraFacts().toMap()

    @Test
    fun `known shelter_type перекладено, а не друкує англійське слово`() {
        val f = facts(mapOf("amenity" to "shelter", "shelter_type" to "basement"))
        assertEquals("підвал", f["Тип укриття"])
    }

    @Test
    fun `навіс отримує слово «навіс», а не «укриття» — canopy не захист`() {
        val f = facts(mapOf("amenity" to "shelter", "shelter_type" to "picnic_shelter"))
        assertEquals("альтанка для пікніка", f["Тип навісу"])
        assertNull(f["Тип укриття"])
    }

    @Test
    fun `невідоме значення shelter_type мовчить, а не друкує себе як є`() {
        val f = facts(mapOf("amenity" to "shelter", "shelter_type" to "some_new_osm_value"))
        assertNull(f["Тип укриття"])
        assertNull(f["Тип навісу"])
    }

    @Test
    fun `barrier lift_gate вже сказаний пігулкою — extraFacts його не дублює`() {
        val f = facts(mapOf("barrier" to "lift_gate"))
        assertNull(f["Тип барʼєру"])
    }

    @Test
    fun `barrier bollard перекладено — пігулка його не бере`() {
        val f = facts(mapOf("barrier" to "bollard"))
        assertEquals("стовпчик", f["Тип барʼєру"])
    }

    @Test
    fun `access permissive живий з Overpass перекладено`() {
        val f = facts(mapOf("access" to "permissive"))
        assertEquals("дозволено власником (може закритись)", f["Режим доступу"])
    }

    @Test
    fun `access private вже в пігулці — рядок не дублюється`() {
        val f = facts(mapOf("access" to "private"))
        assertNull(f["Режим доступу"])
    }

    @Test
    fun `access yes не вартий рядка — це типовий стан`() {
        val f = facts(mapOf("access" to "yes"))
        assertNull(f["Режим доступу"])
    }

    @Test
    fun `wikidata Q-код не показується — людині він нічого не каже`() {
        val f = facts(mapOf("wikidata" to "Q12345"))
        assertFalse(f.containsKey("Wikidata"))
    }

    @Test
    fun `heritage-число перекладено у зрозуміле, а не друкує «2»`() {
        val f = facts(mapOf("heritage" to "2"))
        assertEquals("памʼятка національного значення", f["Охорона"])
    }

    @Test
    fun `network без brand стає єдиною «Мережею»`() {
        val f = facts(mapOf("network" to "Integrovaný dopravní systém"))
        assertEquals("Integrovaný dopravní systém", f["Мережа"])
    }

    @Test
    fun `brand і network разом не дають два рядки «Мережа»`() {
        val f = dossier(mapOf("brand" to "ОККО", "network" to "якась мережа")).extraFacts()
        assertEquals(1, f.count { it.first == "Мережа" })
        assertEquals("ОККО", f.first { it.first == "Мережа" }.second)
    }

    @Test
    fun `fee=yes без charge не додає рядок «Вартість» — двійковий факт уже в піку`() {
        val f = facts(mapOf("fee" to "yes"))
        assertFalse(f.containsKey("Вартість"))
    }

    @Test
    fun `charge живий з Overpass — одиниця часу перекладена, валюта лишається`() {
        val f = facts(mapOf("fee" to "yes", "charge" to "10 CZK/hour"))
        assertEquals("10 CZK/год", f["Вартість"])
    }

    @Test
    fun `fee=no не показує «Вартість» — заклад безкоштовний`() {
        val f = facts(mapOf("fee" to "no", "charge" to "10 CZK/hour"))
        assertFalse(f.containsKey("Вартість"))
    }

    @Test
    fun `surface живий приклад перекладено`() {
        assertEquals("бруківка (плитка)", facts(mapOf("surface" to "paving_stones"))["Дорожнє покриття"])
        assertEquals("трава", facts(mapOf("surface" to "grass"))["Дорожнє покриття"])
    }

    @Test
    fun `smoothness живий приклад перекладено`() {
        assertEquals("середня", facts(mapOf("smoothness" to "intermediate"))["Якість покриття"])
    }

    @Test
    fun `числове з одиницею не чіпається`() {
        assertEquals("5 kW", formatPower("5 kW"))
        assertEquals("150kW", formatPower("150kW"))
    }

    @Test
    fun `невимірене значення потужності не малюється сирим`() {
        assertNull(formatPower("кілька"))
        assertNull(formatPower(""))
    }

    @Test
    fun `generator output мовчить, коли форма не числова`() {
        assertNull(facts(mapOf("generator:output" to "several"))["Потужність генератора"])
    }

    @Test
    fun `платність не зникає з огляду PHANTOM, коли переїхала з extraFacts у пік`() {
        val prompt = dossierBriefPrompt(dossier(mapOf("fee" to "no")))
        assertTrue("платність мусить лишитись фактом для моделі", prompt.contains("Оплата: безкоштовно"))
    }
}
