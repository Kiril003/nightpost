package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.core.geo.place.PlaceGazetteer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrimeaIsUkraineTest {

    private val cities = mapOf(
        "Сімферополь" to (44.952 to 34.102),
        "Севастополь" to (44.616 to 33.525),
        "Ялта" to (44.495 to 34.166),
        "Керч" to (45.357 to 36.468),
    )

    private val shapes by lazy {
        OblastShapes.of(File("src/main/assets/country/ua/oblasts.geojson").readText())
    }

    @Test
    fun `every crimean city stands in its own country`() {
        assertTrue("пакет областей не прочитався", shapes.describesUkraine)
        for ((name, at) in cities) {
            val local = at.inLocalCountry(shapes)
            assertTrue("$name — «за кордоном»", local)
            assertEquals(name, "UA", countryCodeAt(at, local))
            assertEquals(name, "UA", countryCodeAt(at, inLocalCountry = false))
        }
    }

    @Test
    fun `the gazetteer names ukraine for every crimean city`() {
        for ((name, at) in cities) {
            val covering = PlaceGazetteer.coveringAnywhere(at.first, at.second)
            assertTrue("$name: ${covering.map { it.nameUa }}", covering.any { it.nameUa == "Україна" })
            assertTrue("$name: ${covering.map { it.nameUa }}", covering.none { it.id == "w-ru" })
            assertEquals(name, "Україна", PlaceGazetteer.countries(at.first, at.second).first().nameUa)
        }
    }

    @Test
    fun `a far point outside ukraine is still not ukraine`() {
        assertEquals(false, (55.75 to 37.62).inLocalCountry(shapes))
    }
}
