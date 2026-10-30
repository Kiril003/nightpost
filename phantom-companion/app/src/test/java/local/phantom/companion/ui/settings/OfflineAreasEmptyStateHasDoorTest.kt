package local.phantom.companion.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Офлайн-регіонів ще нема · відкрий Мапу, знайди ділянку і збережи її» — порада
 * без дверей і без назви кнопки (кадр 06.09 03:52, A52). Порожній стан мусить
 * вести туди, де це робиться: `mapOpenRequests` → дім → вкладка Мапа.
 */
class OfflineAreasEmptyStateHasDoorTest {

    private val root = File("../").canonicalFile

    private fun read(rel: String): String {
        val f = File(root, rel)
        assertTrue("немає файла $rel", f.isFile)
        return f.readText()
    }

    @Test
    fun `порожній стан має кнопку на мапу через канал вкладки Мапа`() {
        val screen = read("app/src/main/java/local/phantom/companion/ui/settings/MapSettingsScreen.kt")
        val empty = screen.substringAfter("if (regions.isEmpty()) {", "").substringBefore("if (groups.places.isNotEmpty())")
        assertTrue("порожній стан зник", empty.contains("R.string.map_regions_empty)"))
        assertTrue("порожній стан досі має підрядкове пояснення", !screen.contains("Explainer(") && !screen.contains("map_regions_empty_body"))
        assertTrue("банер ліцензії досі на екрані офлайн-мап", !screen.contains("LicenseNotice("))
        assertTrue("сирі dp-літерали на екрані офлайн-мап", !screen.contains("56.dp") && !screen.contains("3.dp"))
        assertTrue("двері порожнього стану не названо", empty.contains("R.string.map_regions_open_map"))
        assertTrue("кнопка порожнього стану нікуди не веде", empty.contains("mapOpenRequests.tryEmit(Unit)"))
        val graph = read("app/src/main/java/local/phantom/companion/navigation/PhantomNavGraph.kt")
        val collector = graph.substringAfter("container.mapOpenRequests.collect {", "")
            .substringBefore("}")
        assertTrue(
            "mapOpenRequests більше не веде на вкладку Мапа — двері намальовані, а за ними стіна",
            collector.contains("homeTabRequest.value") && collector.contains("HomeTab.MAP"),
        )
        val tabCollector = graph.substringAfter("container.homeTabRequest.collect {", "")
            .substringBefore("LaunchedEffect")
        val home = graph.substringAfter("fun home()", "").substringBefore("LaunchedEffect")
        assertTrue(
            "запит вкладки не повертає на дім — з налаштувань мапа відкриється під ними",
            tabCollector.contains("home()") && home.contains("Routes.Home.path"),
        )
    }

    @Test
    fun `рядки порожнього стану й онлайн-геосервісів існують обома мовами без зайвих пояснень`() {
        listOf("values-uk", "values").forEach { dir ->
            val xml = read("app/src/main/res/$dir/strings.xml")
            listOf("map_regions_empty", "map_regions_open_map", "map_online_on", "map_online_off").forEach { key ->
                assertTrue("$dir — немає рядка $key", xml.contains("name=\"$key\""))
            }
            assertTrue("$dir — map_regions_empty_body не прибрано", !xml.contains("name=\"map_regions_empty_body\""))
        }
    }
}
