package local.phantom.companion.core.net.map

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * БЕЗ СВОГО СЕРВЕРА МАРШРУТ НЕ ВИХОДИТЬ У МЕРЕЖУ ВЗАГАЛІ.
 *
 * Донедавна виходив завжди. `routingBasesFor` підставляла за LAN-шаблоном (а
 * також і за порожньою конфігурацією) публічний `routing.openstreetmap.de`,
 * а OSRM кладе ОБИДВІ точки в шлях адреси — тож поза домашнім Wi-Fi, тобто в
 * полі, чужий сервер отримував звідки І куди їде оператор, на кожній
 * перебудові маршруту.
 *
 * Тут міряється не «менше запитів», а НУЛЬ: список основ порожній, а це
 * єдине джерело адрес у [OsrmClient] — обхід по ньому просто не має тіла.
 */
class RoutingEgressTest {

    private val saved = GeoEndpoints.config

    @After
    fun restore() {
        GeoEndpoints.config = saved
    }

    @Test
    fun `порожня конфігурація не дає жодної основи для запиту`() {
        GeoEndpoints.config = GeoEndpointsConfig.DISABLED
        assertEquals(
            "без налаштованого сервера маршрут не має куди йти — і не йде нікуди",
            emptyList<String>(),
            GeoEndpoints.config.routingBasesFor("routed-car"),
        )
        assertNull(GeoEndpoints.config.routingBaseFor("routed-car"))
    }

    @Test
    fun `LAN-основа не тягне за собою чужу`() {
        GeoEndpoints.config = GeoEndpointsConfig.selfHostedLocal("192.168.1.10")
        val bases = GeoEndpoints.config.routingBasesFor("routed-foot")
        assertEquals(
            "рівно одна основа — та, яку поставив оператор. Друга в цьому списку " +
                "означала б чужий сервер, куди їдуть обидві точки поїздки",
            listOf("http://192.168.1.10:8080/routed-foot"),
            bases,
        )
    }

    @Test
    fun `без пакета й без свого сервера клієнт мовчить, а не питає чужих`() = runBlocking {
        GeoEndpoints.config = GeoEndpointsConfig.DISABLED
        val client = OsrmClient()
        val kyiv = 50.4501 to 30.5234
        val lviv = 49.8397 to 24.0297

        assertNull(
            "route() без основи має відмовити, а не знайти собі сервер",
            client.route(RouteProfile.CAR, kyiv.first, kyiv.second, lviv.first, lviv.second),
        )
        assertNull(
            "routeDetailed() без основи має відмовити",
            client.routeDetailed(RouteProfile.CAR, listOf(kyiv, lviv)),
        )
        assertNull("table() без основи має відмовити", client.table(RouteProfile.CAR, kyiv.first, kyiv.second, listOf(lviv)))
        assertNull(
            "match() без основи має відмовити",
            client.match(
                RouteProfile.CAR,
                listOf(Triple(kyiv.first, kyiv.second, 12f), Triple(kyiv.first + 1e-4, kyiv.second, 12f)),
            ),
        )
    }

    /**
     * Форма, а не слово: у клієнті не має бути ЖОДНОЇ зашитої основи. Інакше
     * наступна «щоб не падало» правка поставить її прямо тут, і порожній
     * список основ вище нічого не значитиме.
     */
    @Test
    fun `у клієнті маршрутів немає жодної зашитої адреси`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val src = File(
            dir ?: error("не знайшов корінь дерева"),
            "core-net/src/main/java/local/phantom/companion/core/net/map/OsrmClient.kt",
        )
        assertTrue("не знайшов ${src.path}", src.isFile)
        val code = src.readText()
            .replace(Regex("""/\*\*[\s\S]*?\*/"""), "")
            .replace(Regex("""(?m)//.*$"""), "")
        val hardcoded = Regex(""""https?://[^"]*"""").findAll(code).map { it.value }.toList()
        assertEquals(
            "основа маршрутів приходить лише з GeoEndpoints; зашите тут " +
                "означає сервер, який оператор не може вимкнути: $hardcoded",
            emptyList<String>(),
            hardcoded,
        )
    }
}
