package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.feature.stream.ui.map.peers.PeerRouteFigure
import local.phantom.companion.feature.stream.ui.map.peers.peerRouteGeoJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Чужий маршрут на землі: звідки він береться й коли зникає.
 *
 * Два закони, обидва про те, чого на мапі НЕ буває. Перший — маршрут існує
 * рівно доти, доки живий лист, яким його надіслали: не «поки не перезапустили
 * застосунок» і не «поки хтось не змахнув». Другий — джерело одне, скринька;
 * друге читання журналу розмови дало б дві відповіді на одне питання, і
 * розійшлись би вони саме тоді, коли строк вийшов.
 */
class PeerRouteLayerTest {

    private val t0 = 1_725_381_000_000L

    private fun mark(peerId: String = "them", expires: Long = t0 + 60_000L) =
        PeerRouteFigure(
            peerId = peerId,
            label = "маршрут від Оксана",
            shape = listOf(50.45 to 30.52, 50.46 to 30.54, 50.47 to 30.55),
            expiresAtMs = expires,
        )

    private fun json(figures: List<PeerRouteFigure>, nowMs: Long) =
        peerRouteGeoJson(figures, nowMs)

    @Test
    fun `протухлий маршрут не малюється`() {
        val m = mark(expires = t0 + 60_000L)

        assertTrue("живий маршрут не потрапив на землю", "LineString" in json(listOf(m), t0))
        // Рівно на строку, не «через хвилину після»: межа листа — це межа.
        assertFalse("протухлий лишився на землі", "LineString" in json(listOf(m), t0 + 60_000L))
        assertEquals(EMPTY_FC, json(listOf(m), t0 + 60_001L))
    }

    @Test
    fun `маршрут без листа не існує на мапі`() {
        // Порожня скринька — порожня земля. Ніякого «останнього відомого».
        assertEquals(EMPTY_FC, json(emptyList(), t0))
    }

    @Test
    fun `на землю йде ламана відправника з підписом, чия вона`() {
        val body = json(listOf(mark()), t0)

        assertTrue("підпису немає — лінія нічия", "\"label\":\"маршрут від Оксана\"" in body)
        assertTrue("нема за що вхопитись пальцю", "\"peerId\":\"them\"" in body)
        // `[довгота, широта]`: переставлені кладуть маршрут в інше море.
        assertTrue("координати переставлені", "[30.52,50.45]" in body)
    }

    @Test
    fun `шар живий, а не лише написаний`() {
        val contributions = mapSource("MapGroundContributions.kt")
        assertTrue(
            "шар чужих маршрутів ніде не встановлюється — землю нема чим малювати",
            "installPeerRoutes(" in contributions && "updatePeerRoutes(" in contributions,
        )
        assertTrue(
            "у шар не заходить палець — картку нема як відкрити",
            "PEER_ROUTE_LAYER" in contributions,
        )
        assertTrue(
            "дотик по чужому маршруту нікуди не веде",
            "ui.selectedPeerRoute =" in mapSource("MapCanvasWiring.kt"),
        )
        assertTrue(
            "картки немає — «Їхати цим маршрутом» нема звідки натиснути",
            "ui.selectedPeerRoute" in mapSource("SheetHost.kt"),
        )
    }

    @Test
    fun `мапа бере маршрути зі скриньки, а не з журналу розмови`() {
        val feed = mapSource("PeerRouteFeed.kt")

        // Другий читач того самого факту — це друга правда. Декодувати дріт
        // тут означало б, що мапа сама вирішує, який лист свіжий.
        assertTrue("поріг не читає скриньку", "viewModel.peerRoutes" in feed)
        assertFalse("поріг розбирає дріт сам", "decodeRoute(" in feed)
        assertFalse("поріг ходить у журнал розмови", "PeerOutbox" in feed)
    }

    @Test
    fun `узяти чужий маршрут означає перепланувати від себе`() {
        val vm = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/" +
                "MapViewModel.kt",
        ).readText()
        val take = vm.substringAfter("fun takePeerRoute(", "").take(400)

        assertTrue("дії немає взагалі", take.isNotEmpty())
        assertTrue("у план не заходить свій початок", "fromLat to fromLon" in take)
        assertTrue("точки відправника загубились", "route.waypoints" in take)
        // Чужа ламана — малюнок: вона почалась не там, де стоїть той, хто її
        // отримав, і поїхати нею означало б рушити звідти, де тебе немає.
        assertFalse("у план заїхала чужа ламана", "route.shape" in take)
    }

    @Test
    fun `чужий маршрут збирає рівно один файл, і той читає лист`() {
        // Дзеркало закону про чужу ТОЧКУ: тієї миті, коли маршрут можна
        // зібрати з реєстру, зʼявляється шар «куди всі їдуть» — навіть якщо
        // його ніхто не хотів. Тому збирач мусить лишатись один і читати лист.
        val builders = sourcesUnder("app/src/main", "feature-stream/src/main")
            .filter { "RouteMark(" in it.readText() }
            .map { it.name }
            .toSet()
        assertEquals("новий збирач чужого маршруту: $builders", setOf("PeerLiveHub.kt"), builders)

        val hub = File(
            repoRoot(),
            "app/src/main/java/local/phantom/companion/data/PeerLiveHub.kt",
        ).readText()
        assertTrue(
            "маршрути збираються не з листів, а з чогось іншого",
            "PeerLiveInbox.absorbRoute(" in hub && "routes.values.map" in hub,
        )
        assertTrue("мітла строків не заведена", "PeerLiveInbox.sweepRoutes(" in hub)
    }

    @Test
    fun `картка не переживає лист, з якого зібрана`() {
        val sheet = mapSource("SheetHost.kt")

        // Копія з миті дотику пережила б сплив: лінія з землі зникла, а
        // картка й далі пропонує «Їхати цим маршрутом».
        assertTrue(
            "картка тримає копію замість живого списку",
            "livePeerRoutes.firstOrNull" in sheet,
        )
        assertTrue(
            "картка не закривається, коли маршрут віджив своє",
            "if (peerRoute == null) ui.selectedPeerRoute = null" in sheet,
        )
    }

    private fun sourcesUnder(vararg roots: String): List<File> = roots.flatMap { root ->
        File(repoRoot(), root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path }
            .toList()
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun mapSource(name: String): String =
        File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/$name",
        ).readText()
}
