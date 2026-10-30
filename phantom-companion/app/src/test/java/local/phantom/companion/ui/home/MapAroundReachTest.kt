package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** «Поруч» досяжне з вкладки Мапа: MapTab дає двері місць, хром мапи ставить аркуш головним. */
class MapAroundReachTest {
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    private fun code(rel: String): String =
        File(repoRoot(), rel).readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")

    private val map = "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map"

    @Test
    fun mapTabGivesTheMapRealPlaces() {
        val tab = code("app/src/main/java/local/phantom/companion/ui/home/MapTab.kt")
        assertTrue(tab.contains("aroundPort = local.phantom.companion.data.MapAroundSource("))
        assertTrue(tab.contains("venues = container.venues"))
        assertTrue(tab.contains("container.poiRepository.getNearbyResult("))
    }

    @Test
    fun aroundYouIsTheFocalSheetAndLayersAreASmallControl() {
        val chrome = code("$map/MapChrome.kt")
        assertTrue("край аркуша має бути «Поруч»", chrome.contains("MapHomePeekBar(") && chrome.contains("onOpenAround = { MapBottomSurface.open(ui, MapBottomOwner.PEOPLE) }"))
        assertTrue(chrome.contains("AroundYouContent("))
        assertTrue("місця мають питатись у моделі", chrome.contains("viewModel.loadAround("))
        assertTrue("шари — мала кнопка", chrome.contains("map-layers-orb") && chrome.contains("MapBottomSurface.open(ui, MapBottomOwner.LAYERS)"))
        assertFalse("старий край «Шари й офлайн» лишився", chrome.contains("MapLayersPeek("))
        val vm = code("$map/MapViewModel.kt")
        assertTrue(vm.contains("aroundPort.places("))
    }

    @Test
    fun placePageFromAroundYouCarriesPhotoRatingAndSendToChat() {
        val chrome = code("$map/MapChrome.kt")
        assertTrue("тап місця має нести оцінку", chrome.contains("ui.dossierTarget = p.dossierTarget()"))
        val page = code("$map/dossier/PlaceDossierSheet.kt")
        assertTrue(page.contains("placeHeroUrl(model)?.let { PlaceHero(it) }"))
        assertFalse("рейтинг прибрано зі сторінки місця (24.10)", page.contains("placeRatingLine("))
        val host = code("$map/SheetHost.kt")
        assertTrue("«У чат» має відкривати вибір людини", host.contains("onSendToChat = {") && host.contains("PlaceSendSheet("))
        assertTrue(host.contains("viewModel.people.sendPlace(id, placeSend.lat, placeSend.lon, name)"))
        assertTrue("«Зустрітися тут» з досьє має йти в чернетку зустрічі",
            host.contains("meet(target.lat, target.lon, dossier?.title ?: target.nameHint, target.venueId)"))
        val tab = code("app/src/main/java/local/phantom/companion/ui/home/MapTab.kt")
        assertTrue(tab.contains("WebMessengerMeetupDraft.meetHere("))
        assertTrue("«Зустрітися тут» має нести заклад, а не голу точку",
            tab.contains("onMeetHere = { lat, lon, label, venueId ->") &&
                tab.contains("WebMessengerMeetupDraft.meetHere(lat, lon, label, venueId = venueId)"))
    }
}
