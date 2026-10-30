package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Поверхня мапи говорить однією мовою — ратчет, не побажання.
 *
 * Власник (03.10): «найгірше — місця, де лишилось СТАРЕ: такий контраст між
 * старим мотлохом і новим». На мапі нові контроли (поле пошуку, чипи дій,
 * аркуш «Шари») стояли на токенах v1.2, а пігулки, платівка, компас, пошук,
 * поверхи й офлайн-панель — на склянці з бурштином стану, моноширинних
 * кікерах і значках Material. Цей сторож тримає хром пакета на токенах:
 *
 *  1. жодного старого словника (тема-спека, бурштин стану, кікер, дані-моно,
 *     склянка, `Icons.`) у файлах хрому;
 *  2. жодного сирого `Color(0x…)` у хромі — кольори малювання живуть у
 *     палітрах ([MapLayerPalette], `indoor/IndoorPalette`), не в пігулках;
 *  3. офлайн-мапи — ОДИН список: Профіль › Офлайн-мапи групує через
 *     [OfflineMapsCatalog] і видаляє місце цілим, а панель на мапі не має
 *     власного переліку, але лишає коридор, шари з ПК і двері в Профіль.
 *
 * ЗАКОН ВОРІТ (спільний із рештою firewall-тестів): скан читає файли як
 * текст, Gradle залежності не бачить — ганяти з `--rerun` на кожних воротах.
 */
class MapSurfaceLookFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun source(path: String): File {
        val file = File(repoRoot(), path)
        assertTrue("$path не знайдено — перевірка вже нічого не боронить", file.isFile)
        return file
    }

    /** Рядки коду: коментар не малює нічого, а імпорт — малює (він і є залежність). */
    private fun codeLines(path: String): List<IndexedValue<String>> =
        source(path).readLines().withIndex().filterNot { (_, line) ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

    private val map = "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map"
    private val settings = "app/src/main/java/local/phantom/companion/ui/settings/MapSettingsScreen.kt"

    /** Хром пакета map-surface: усе, що людина бачить на мапі й у її аркушах. */
    private val chrome = listOf(
        "MapChrome.kt",
        "MapScreen.kt",
        "MapChromeParts.kt",
        "MapControls.kt",
        "DeclinationReadout.kt",
        "LightReadout.kt",
        "MapToolsCapsule.kt",
        "MapCardParts.kt",
        "MapSearchSheet.kt",
        "MapSearchSheetShell.kt",
        "MapSearchFirstScreen.kt",
        "MapSearchResults.kt",
        "MapSearchRows.kt",
        "indoor/IndoorFloorOverlay.kt",
        "indoor/IndoorLevelSwitcher.kt",
        "OfflineTerritoriesPanel.kt",
        "OfflinePlacePicker.kt",
        "OfflinePlacesSection.kt",
        "PcLayerPacksSection.kt",
    ).map { "$map/$it" } + settings

    /** Старий словник: тема-спека, бурштин стану, моно-кікери, склянка, Material-значки. */
    private val oldVocabulary = listOf(
        "LocalStateAccent",
        "LocalPhantomTheme",
        "PhantomDataText",
        "PhantomKicker",
        "Icons.",
        "mapGlass",
        "glassPanel",
    )

    @Test
    fun `map chrome speaks no old vocabulary`() {
        val offenders = chrome.flatMap { path ->
            codeLines(path)
                .filter { (_, line) -> oldVocabulary.any { line.contains(it) } }
                .map { (i, line) -> "${path.substringAfterLast('/')}:${i + 1} ${line.trim()}" }
        }
        assertEquals(
            "старий словник повернувся в хром мапи:\n" + offenders.joinToString("\n") +
                "\n\nКольори — LocalPhantomColors, текст — PhantomText, значки — lc_* або " +
                "PhantomGlyph, поверхні — MapCardParts.kt (raised/overlay/line).",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `map chrome paints no raw hex — palettes own the drawing colours`() {
        val offenders = chrome.flatMap { path ->
            codeLines(path)
                .filter { (_, line) -> line.contains("Color(0x") }
                .map { (i, line) -> "${path.substringAfterLast('/')}:${i + 1} ${line.trim()}" }
        }
        assertEquals(
            "сирий колір у хромі мапи:\n" + offenders.joinToString("\n") +
                "\n\nКолір стану — токен (success/danger/warning), колір дії — accent; " +
                "колір шару чи кімнати — у MapLayerPalette / IndoorPalette.",
            emptyList<String>(),
            offenders,
        )
        // Палітри існують і справді несуть те, що з хрому винесли.
        assertTrue(source("$map/MapLayerPalette.kt").readText().contains("Color(0x"))
        assertTrue(source("$map/indoor/IndoorPalette.kt").readText().contains("Color(0x"))
    }

    @Test
    fun `profile offline maps is the one grouped list`() {
        val body = codeLines(settings).joinToString("\n") { it.value }
        assertTrue(
            "Профіль › Офлайн-мапи більше не групує через OfflineMapsCatalog — " +
                "місця знову розсипаться на шматки",
            body.contains("OfflineMapsCatalog.group("),
        )
        assertTrue(
            "місце в Профілі видаляється не цілим (catalog.deletePlace)",
            body.contains("catalog.deletePlace("),
        )
        assertTrue(
            "Профіль знову видаляє повз фасад, одним дотиком і шматок за шматком",
            !body.contains("offlineRegions.delete("),
        )
        assertTrue(
            "друге торкання зникло: видалення в Профілі знову одним дотиком",
            body.contains("arm(key)") && body.contains("map_offline_delete_confirm_sized"),
        )
        assertTrue(
            "міграцію старих метаданих не викликано — давні коридори ляжуть серед ділянок",
            body.contains("catalog.migrateLegacyMetadata()"),
        )
        assertTrue(
            "«прибрати всі» для коридорів і ділянок зникло",
            body.contains("ClearGroupRow(") && body.contains("catalog.deleteAll("),
        )
    }

    @Test
    fun `the map panel keeps its view tools and has no second list`() {
        val panel = codeLines("$map/OfflineTerritoriesPanel.kt").joinToString("\n") { it.value }
        listOf("PackGroup", "PackRow", "onDelete", "PanelSearchField", "manager.delete(").forEach {
            assertTrue("офлайн-панель знову веде власний перелік ($it)", !panel.contains(it))
        }
        assertTrue("картка коридора зникла — коридор маршруту більше нічим зберегти", panel.contains("CorridorCard("))
        assertTrue("шари з ПК зникли з панелі", panel.contains("PcLayerPacksSection("))
        assertTrue("двері до вибору місця зникли", panel.contains("OfflinePlacesSection("))
        assertTrue(
            "рядок «Усі офлайн-мапи» зник — перелік лишився без входу з мапи",
            panel.contains("R.string.map_offline_all_maps") && panel.contains("onClick = onOpenAll"),
        )
        val screen = codeLines("$map/MapScreen.kt").joinToString("\n") { it.value }
        assertTrue(
            "панель не отримує onOpenPacks — двері намальовані, а за ними стіна",
            screen.contains("onOpenAll = onOpenPacks"),
        )
        val places = codeLines("$map/OfflinePlacesSection.kt").joinToString("\n") { it.value }
        assertTrue(
            "секція місць знову малює власні рядки збереженого",
            !places.contains("PlaceStatusRow") && !places.contains("deletePlace("),
        )
    }

    @Test
    fun `map screen paints no status bar gradient scrim or sky wash`() {
        val screen = codeLines("$map/MapScreen.kt").joinToString("\n") { it.value }
        listOf("StatusBarScrim", "STATUS_SCRIM_FADE", "Brush.verticalGradient", "skyWashFor(").forEach {
            assertTrue("MapScreen досі малює $it", !screen.contains(it))
        }
    }

    @Test
    fun `search open hides top map chrome and pads search sheet by bottom inset`() {
        val screen = codeLines("$map/MapScreen.kt").joinToString("\n") { it.value }
        val chrome = codeLines("$map/MapChrome.kt").joinToString("\n") { it.value }
        val band = codeLines("$map/MapHomeControls.kt").joinToString("\n") { it.value }.substringAfter("fun MapHomeTopBand(")
        assertTrue(
            "смуга пошуку MapChrome лишається над відкритою шторкою пошуку — дві пошукові смуги одночасно",
            chrome.contains("showSearch = !ui.searchOpen,") &&
                band.substringAfter("if (showSearch) {").trimStart().startsWith("MapSearchField("),
        )
        assertTrue(
            "MapChrome зникає з композиції під час пошуку — тривоги й підказки маршруту гаснуть",
            !screen.contains("if (!ui.searchOpen)"),
        )
        val searchBlock = screen.substringAfter("visible = ui.searchOpen,").substringBefore("enter =")
        assertTrue(
            "MapSearchSheet не має відступу LocalBottomChromeInset.current",
            searchBlock.contains("LocalBottomChromeInset.current"),
        )
        val offlineWrapper = screen.substringAfter("visible = ui.offlineOpen,").substringBefore("enter =")
        assertTrue(
            "обгортка OfflineTerritoriesPanel дублює LocalBottomChromeInset.current",
            !offlineWrapper.contains("LocalBottomChromeInset.current"),
        )
    }

    @Test
    fun `no hardcoded Cyrillic literals in MapScreen, MapTab, or MapSettingsScreen`() {
        val targets = listOf(
            "$map/MapScreen.kt",
            "app/src/main/java/local/phantom/companion/ui/home/MapTab.kt",
            settings,
        )
        val stringLiteral = Regex(""""([^"\\]|\\.)*"""")
        val cyrillic = Regex("""[\u0400-\u04FF]""")
        val offenders = targets.flatMap { path ->
            codeLines(path).flatMap { (i, line) ->
                stringLiteral.findAll(line)
                    .filter { cyrillic.containsMatchIn(it.value) }
                    .map { "${path.substringAfterLast('/')}:${i + 1} ${it.value}" }
                    .toList()
            }
        }
        assertEquals(
            "кириличні рядкові літерали в коді замість ресурсів:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }
}
