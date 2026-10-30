package local.phantom.companion.core.data.geocoder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Відповідь телефона проти відповіді кузні, рядок у рядок.
 *
 * Очікування пораховано `forge/geocode.py` над ТИМ САМИМ файлом, що читає цей
 * тест. Це двомовний контракт: розійтися можна лише зламавши згортку, вибір
 * триграм, SQL або порядок ваг — тобто рівно те, що робить пошук пошуком.
 */
class ForgeGeocoderVectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var geocoder: ForgeGeocoder

    private val expected: JsonObject by lazy {
        Json.parseToJsonElement(ForgePackFixture.resource("forge/geocoder-md-slice-vectors.json")).jsonObject
    }

    @Before
    fun open() {
        val file = ForgePackFixture.unpack(temp.newFolder("forge"))
        geocoder = ForgeGeocoder.over(JdbcPackDb(file.absolutePath))
            ?: error("пакет кузні не відкрився")
    }

    @After
    fun close() {
        geocoder.close()
    }

    @Test
    fun `every forge query answers exactly as the forge does`() {
        val vectors = expected["vectors"]!!.jsonArray
        var searches = 0
        for (element in vectors) {
            val vector = element.jsonObject
            if (vector.containsKey("reverse")) continue
            searches++
            val query = vector["q"]!!.jsonPrimitive.content
            val near = vector["near"].coordinates()
            val hits = geocoder.search(query, near, limit = 5)
            assertEquals("запит «$query»", vector["top"]!!.jsonArray.describe(), hits.describe())
        }
        assertTrue("вектори кузні не прочитались", searches >= 13)
    }

    @Test
    fun `reverse lookup names the same place the forge named`() {
        val vectors = expected["vectors"]!!.jsonArray
        var reverses = 0
        for (element in vectors) {
            val vector = element.jsonObject
            val point = vector["reverse"] ?: continue
            reverses++
            val (lat, lon) = point.coordinates()!!
            val hit = geocoder.reverse(lat, lon)
            val want = vector["hit"]!!.jsonObject
            assertNotNull("зворотний $lat,$lon", hit)
            assertEquals(want["kind"]!!.jsonPrimitive.content, hit!!.kind)
            assertEquals(want["name"]!!.jsonPrimitive.content, hit.name)
            assertEquals(want["osm_id"]!!.jsonPrimitive.long, hit.osmId)
            assertEquals(want["distance_m"]!!.jsonPrimitive.double, hit.distanceMeters!!, 0.5)
        }
        assertEquals(2, reverses)
    }

    @Test
    fun `a gazebo is not a shelter`() {
        val near = 47.0105 to 28.8638
        val shelters = geocoder.search("укриття", near, limit = 5)
        assertEquals(2, shelters.size)
        assertEquals("Bomb shelter", shelters.first().name)
        assertEquals("shelter", shelters.first().cat)
        assertEquals(emptyList<String>(), geocoder.search("альтанка", near, limit = 5).map { it.name })
    }

    @Test
    fun `a typo still finds the capital`() {
        val typo = geocoder.search("Кишенів", null, limit = 5)
        assertEquals("Chișinău", typo.first().name)
        assertEquals("place", typo.first().kind)
    }

    @Test
    fun `the pack declares the format this reader knows`() {
        assertEquals(ForgeGeocoder.FORMAT.toString(), geocoder.meta["format"])
        assertEquals("MD", geocoder.meta["country"])
        assertEquals("fts4", geocoder.meta["fts"])
        assertEquals("Укриття", geocoder.categoryLabels["shelter"])
    }

    @Test
    fun `a pack of another format does not open`() {
        val alien = temp.newFile("alien.sqlite")
        alien.writeText("not a database")
        assertEquals(null, ForgeGeocoder.over(JdbcPackDb(alien.absolutePath)))
    }

    private fun List<ForgeHit>.describe(): List<String> =
        map { "${it.kind}/${it.osmId}/${it.name}/${it.distanceMeters?.toInt()}" }

    private fun JsonArray.describe(): List<String> = map { element ->
        val row = element.jsonObject
        val distance = row["distance_m"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
        "${row["kind"]!!.jsonPrimitive.content}/${row["osm_id"]!!.jsonPrimitive.long}/" +
            "${row["name"]!!.jsonPrimitive.content}/$distance"
    }

    private fun JsonElement?.coordinates(): Pair<Double, Double>? {
        if (this == null || this is JsonNull) return null
        val pair = jsonArray
        return pair[0].jsonPrimitive.double to pair[1].jsonPrimitive.double
    }
}
