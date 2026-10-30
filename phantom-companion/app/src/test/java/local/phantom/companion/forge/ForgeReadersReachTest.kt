package local.phantom.companion.forge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * СТОРОЖ ДОСЯЖНОСТІ трьох читачів кузні.
 *
 * Памʼятка «написане, але не викликане» коштувала цій кампанії трьох
 * доведених, протестованих і мертвих підсистем за два дні. Розібрати пакет
 * уміє тест поруч із читачем; ЦЕЙ тест питає інше — чи є в читача споживач на
 * екрані, і читає для цього самі джерела, як [MapDepthFirewallTest].
 */
class ForgeReadersReachTest {

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "app").isDirectory || !File(dir, "core-geo").isDirectory) {
            dir = dir.parentFile ?: error("кореня репозиторію не знайдено")
        }
        return dir
    }

    private fun source(path: String): String {
        val file = File(repoRoot(), path)
        assertTrue("немає $path", file.isFile)
        return file.readLines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    @Test
    fun `the geocoder is handed to the repository the map search asks`() {
        val container = source("app/src/main/java/local/phantom/companion/data/AppContainer.kt")
        assertTrue(
            "PoiRepository мусить отримати УСІ поставлені геокодери кузні, а не один",
            container.contains("forgeGeocoders ="),
        )
        assertTrue(
            "нечіткий прохід мусить знати, у якій країні людина стоїть",
            container.contains("homeCountry ="),
        )
        assertTrue(container.contains("ForgeGeocoderStore"))

        val repository = source("core-data/src/main/java/local/phantom/companion/core/data/poi/PoiRepository.kt")
        assertTrue(
            "пакет мусить питатись усередині вироку пошуку, а не збоку",
            repository.substringAfter("suspend fun searchOfflineVerdict").contains("forgeSearch("),
        )

        val surface = source("feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapPoiSearch.kt")
        assertTrue(
            "видача мапи мусить лишитись однією — тією, що читає вирок",
            surface.contains("poiRepository.searchOfflineVerdict("),
        )
    }

    @Test
    fun `the terrain pack reaches the style and the hillshade layer`() {
        val canvas = source("feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapCanvas.kt")
        assertTrue("полотно мусить приймати пакет рельєфу", canvas.contains("terrainPack: TerrainPackInfo?"))
        assertTrue("і вносити його в стиль", canvas.contains("applyLocalTerrain("))
        assertTrue("стеля пакета мусить доїжджати до шару", canvas.contains("terrainPackMaxZoom = terrainPack?.maxZoom"))

        val screen = source("feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapScreen.kt")
        assertTrue("екран мусить читати сховище рельєфу", screen.contains("TerrainPackStore(context)"))
        assertTrue(screen.contains("terrainPack = terrainPack"))

        val ground = source(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapGroundContributions.kt",
        )
        assertTrue(
            "шар рельєфу мусить брати стелю з пакета, а не з мережевої",
            ground.contains("installHillshade(s, mapIsDark, i.terrainPackMaxZoom"),
        )
    }

    @Test
    fun `the forge catalog reaches the list a finger taps`() {
        val card = source("feature-precision/src/main/java/local/phantom/companion/feature/precision/CloudPacksCard.kt")
        assertTrue("картка мусить читати каталог кузні", card.contains("catalogClient.forgeCatalog()"))
        assertTrue("і малювати його рядки", card.contains("ForgePackRow("))
        assertTrue("брама місць", card.contains("GeocoderPackGate(context)"))
        assertTrue("брама рельєфу", card.contains("TerrainPackGate(context)"))
        assertTrue(
            "невідомий рід не має мовчки їхати в чужу браму",
            card.contains("ForgePackKind.BASEMAP_PMTILES -> null"),
        )
    }

    @Test
    fun `each reader is named in the language of the person holding the phone`() {
        val ua = source("feature-precision/src/main/res/values-uk/strings.xml")
        val en = source("feature-precision/src/main/res/values/strings.xml")
        for (key in listOf("forge_packs_title", "forge_packs_kind_geocoder", "forge_packs_kind_terrain")) {
            assertTrue("немає $key українською", ua.contains("\"$key\""))
            assertTrue("немає $key англійською", en.contains("\"$key\""))
        }
    }
}
