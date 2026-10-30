package local.phantom.companion.feature.stream.geo

import local.phantom.companion.core.nav.RoadRefusal
import local.phantom.companion.core.nav.refusalWithoutRouter
import local.phantom.companion.core.net.map.GeoEndpoints
import local.phantom.companion.core.net.map.GeoEndpointsConfig
import local.phantom.companion.core.net.map.routingBasesFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * НІ ПАКЕТА, НІ СВОГО СЕРВЕРА: мережі немає — і людина чує ЧОМУ.
 *
 * Друга половина доказу 20.09. Перша ([RoutingEgressTest] у :core-net) міряє,
 * що запиту нема взагалі. Ця міряє, що на місці запиту лишився не тихий нуль,
 * а названа відмова з дорогою до ліку — дорожнього пакета країни.
 *
 * Обидві потрібні разом: прибрати чужий сервер і лишити мовчазну відмову
 * означало б полікувати приватність ціною маршруту, тобто повторити ту саму
 * помилку, якою публічний OSRM сюди й потрапив.
 */
class RouteRefusalWithoutRouterTest {

    private val saved = GeoEndpoints.config

    @After
    fun restore() {
        GeoEndpoints.config = saved
    }

    @Test
    fun `без свого сервера мережеве плече не має куди йти`() {
        GeoEndpoints.config = GeoEndpointsConfig.DISABLED
        assertEquals(
            emptyList<String>(),
            GeoEndpoints.config.routingBasesFor("routed-foot"),
        )
    }

    @Test
    fun `нема доріг на пристрої і нема сервера — окрема названа відмова`() {
        assertEquals(
            "«нема доріг тут» без сервера — інший світ, ніж «нема доріг тут» із сервером",
            RoadRefusal.NO_PACK_NO_SERVER,
            refusalWithoutRouter(RoadRefusal.NO_DATA, ownRouterConfigured = false),
        )
        assertEquals(
            "граф змовчав, сервера немає — відмова все одно мусить бути названа",
            RoadRefusal.NO_PACK_NO_SERVER,
            refusalWithoutRouter(null, ownRouterConfigured = false),
        )
        assertEquals(
            "сервер налаштований — лік інший, і підміняти причину не можна",
            RoadRefusal.NO_DATA,
            refusalWithoutRouter(RoadRefusal.NO_DATA, ownRouterConfigured = true),
        )
    }

    @Test
    fun `причини з власним ліком не перетираються відсутністю сервера`() {
        // Пакет у людини вже стоїть; послати її по пакет — послати по те, що є.
        for (own in listOf(RoadRefusal.OFF_ROAD, RoadRefusal.SEARCH_CEILING, RoadRefusal.TOO_LONG)) {
            assertEquals(own, refusalWithoutRouter(own, ownRouterConfigured = false))
            assertEquals(own, refusalWithoutRouter(own, ownRouterConfigured = true))
        }
    }

    /**
     * Кожен [RoadRefusal] має РЕЧЕННЯ обома мовами. Компілятор стежить лише за
     * тим, що гілка `when` існує; за тим, що за нею стоїть рядок, який людина
     * зрозуміє, не стежить ніхто.
     */
    @Test
    fun `кожна причина відмови має рядок і українською, і англійською`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: error("не знайшов корінь дерева")
        val uk = File(root, "feature-stream/src/main/res/values-uk/strings.xml").readText()
        val en = File(root, "feature-stream/src/main/res/values/strings.xml").readText()

        val missing = RoadRefusal.entries.flatMap { r ->
            val name = "route_reason_" + r.name.lowercase()
            listOfNotNull(
                "values-uk/$name".takeIf { "\"$name\"" !in uk },
                "values/$name".takeIf { "\"$name\"" !in en },
            )
        }
        assertEquals("причина без речення — це мовчазний нуль: $missing", emptyList<String>(), missing)

        val line = Regex("""<string name="route_reason_no_pack_no_server">(.*?)</string>""")
            .find(uk)?.groupValues?.get(1).orEmpty()
        assertTrue(
            "відмова мусить вести до ліку — до дорожнього пакета країни, а не в нікуди: «$line»",
            "Хмарні пакети" in line,
        )
    }
}
