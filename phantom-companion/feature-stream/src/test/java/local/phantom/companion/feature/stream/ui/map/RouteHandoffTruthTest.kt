package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.core.net.peer.PeerLive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Речення про надсилання маршруту мусить бути правдою в тому стані, який видно.
 *
 * `RouteHandoff.NoRoute` виставлявся у двох місцях і обидва показували
 * «маршруту ще немає — спершу прокладіть його». Перше місце (`variant == null ||
 * pins == null`) з чіпа недосяжне: чіп малюється лише в блоці маршруту, тобто
 * коли маршрут уже є. Друге (`body == null`) — досяжне, і там маршрут у людини
 * НА ЕКРАНІ. Продукт радив прокласти те, що вже прокладено.
 *
 * Сторож на досяжність, а не на правильність функції: доводить, що маршрут із
 * тринадцятьма точками справді не пакується (отже стан не вигаданий), і що
 * гілка `body == null` веде до нового речення, а не до старого.
 */
class RouteHandoffTruthTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь репозиторію не знайдено") as Nothing
    }

    private fun source(path: String): String {
        val f = File(repoRoot(), path)
        assertTrue("$path не знайдено — сторож нічого не боронить", f.isFile)
        return f.readText()
    }

    private fun route(stops: Int) = PeerLive.Route(
        expiresAtMs = 1_788_000_000_000L,
        distanceM = 12_000.0,
        durationS = 900L,
        profile = "CAR",
        waypoints = List(stops) { 50.45 + it * 0.001 to 30.52 + it * 0.001 },
        shape = List(stops * 2) { 50.45 + it * 0.0005 to 30.52 + it * 0.0005 },
    )

    @Test
    fun `a route with thirteen stops really does not pack — the state is not invented`() {
        assertNull("тринадцять точок мали б не влізти", PeerLive.encodeRoute(route(13)))
        assertNotNull("дванадцять точок — межа, вони мають влізти", PeerLive.encodeRoute(route(12)))
        assertNotNull("звичайні дві точки пакуються", PeerLive.encodeRoute(route(2)))
    }

    @Test
    fun `the reachable branch no longer says the route is missing`() {
        val vm = source("feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapViewModel.kt")
        assertTrue("стан TooBig не заведено", vm.contains("data class TooBig(val tooManyStops: Boolean)"))
        assertTrue(
            "гілка body == null більше не веде до TooBig",
            vm.contains("RouteHandoff.TooBig("),
        )
        // Зустрічний: якщо хтось поверне NoRoute у гілку «маршрут є, лист не склався»,
        // сторож має почервоніти саме тут, а не десь у вигляді.
        val afterBodyNull = vm.substringAfter("if (body == null) {").substringBefore("}")
        assertFalse(
            "у гілку «лист не склався» повернувся NoRoute — речення знову бреше",
            afterBodyNull.contains("RouteHandoff.NoRoute"),
        )
        assertTrue(
            "причину не розрізняють за кількістю точок",
            vm.contains("pins.size > local.phantom.companion.core.net.peer.PeerLive.MAX_WAYPOINTS"),
        )
    }

    @Test
    fun `the sheet speaks the new sentences, in both languages`() {
        val sheet = source("feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapCommandSheet.kt")
        assertTrue("шторка не знає нового стану", sheet.contains("RouteHandoff.TooBig"))
        assertTrue("немає речення про точки", sheet.contains("R.string.peer_route_too_many_stops"))
        assertTrue("немає загального речення", sheet.contains("R.string.peer_route_unsendable"))

        val uk = source("feature-stream/src/main/res/values-uk/strings.xml")
        val en = source("feature-stream/src/main/res/values/strings.xml")
        listOf("peer_route_too_many_stops", "peer_route_unsendable").forEach {
            assertTrue("uk не має $it", uk.contains("name=\"$it\""))
            assertTrue("en не має $it", en.contains("name=\"$it\""))
        }
        // Межа, названа словом, мусить збігатися з межею в коді: речення, яке
        // обіцяє інше число, — та сама неправда, лише тихіша.
        assertTrue(
            "у реченні названо не ту межу, що в PeerLive.MAX_WAYPOINTS (${PeerLive.MAX_WAYPOINTS})",
            uk.contains("не більше ${PeerLive.MAX_WAYPOINTS}") && en.contains("${PeerLive.MAX_WAYPOINTS} at most"),
        )
    }
}
