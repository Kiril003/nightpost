package local.phantom.companion.core.data.poi.bench

import java.io.File
import kotlin.math.cos
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.geocoder.ForgeGeocoder
import local.phantom.companion.core.data.geocoder.ForgePackFixture
import local.phantom.companion.core.data.geocoder.JdbcPackDb
import local.phantom.companion.core.data.poi.PoiDensity
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiRepository
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Прилад значків: що `getPois` віддає мапі в центрі міста без мережі.
 * Кадр — A52 (411×914 dp), як у PlaceFrameDensityTest. «До» — вікно, яке
 * брала підлога значків до 28.09 (`nearestInBox` з вулицями й поселеннями).
 */
class PinsBenchTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun frame(lat: Double, lon: Double, zoom: Double): DoubleArray {
        val world = 512.0 * Math.pow(2.0, zoom)
        val dLon = 360.0 * 411.0 / world
        val dLat = 360.0 * 914.0 / world * cos(Math.toRadians(lat))
        return doubleArrayOf(lat - dLat / 2, lon - dLon / 2, lat + dLat / 2, lon + dLon / 2)
    }

    private val chisinau = City("Кишинів (пакет кузні MD)", 47.0245, 28.8323, hasPack = true)

    @Test
    fun `every city with a pack gets real place pins offline`() {
        val dao = UaCorpus.dao()
        val md = ForgeGeocoder.over(JdbcPackDb(ForgePackFixture.unpack(temp.newFolder("md")).absolutePath))!!
        val repo = PoiRepository(
            poiDao = UaCorpus.NoCache,
            gazetteer = dao,
            offlinePack = { UaCorpus.pack },
            forgeGeocoders = { listOf(md) },
        )
        val out = StringBuilder("# Значки без мережі — що доходить до мапи\n\n")
        out.appendLine("| місто | зум | до: вікно газетира (місць/вулиць/поселень) | після: значків | місць | родів |")
        out.appendLine("|---|---|---|---|---|---|")
        val bad = ArrayList<String>()
        for (city in SearchProbes.CITIES + chisinau) {
            for (zoom in listOf(13.0, 15.0, 17.0)) {
                val b = frame(city.lat, city.lon, zoom)
                val cap = PoiDensity.capForZoom(zoom)
                val k = cos(Math.toRadians(city.lat)).let { it * it }
                val before = runBlocking { dao.nearestInBox(b[0], b[2], b[1], b[3], city.lat, city.lon, k, cap * 4) }
                val bStreets = before.count { it.category == "street" }
                val bSettl = before.count { it.category == "place" }
                val pins: List<PoiEntity> = runBlocking {
                    repo.getPois(b[0], b[1], b[2], b[3], zoom).toList().lastOrNull().orEmpty()
                }
                val noise = pins.count { it.category == "street" || it.category == "place" }
                val places = pins.size - noise
                val kinds = pins.map { it.subcategory ?: it.category }.toSet().size
                out.appendLine(
                    "| ${city.name} | ${zoom.toInt()} | ${before.size - bStreets - bSettl}/$bStreets/$bSettl | " +
                        "${pins.size} | $places | $kinds |",
                )
                // На z17 кадр — квартал, і місць у ньому буває менше за стелю: там міряється лише шум.
                if (city.hasPack && ((zoom < 16.0 && places < cap) || noise > 0)) {
                    bad += "${city.name} z$zoom: місць $places, шуму $noise"
                }
            }
        }
        File("build/reports").mkdirs()
        File("build/reports/pins-bench.md").writeText(out.toString())
        println(out)
        assertTrue("мапа гола або засмічена: $bad", bad.isEmpty())
    }
}
