package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Порядок реєстрації всередині ярусу Є порядком укладання шарів.
 *
 * Смуги руху НЕ мають власного ярусу — вони живуть на [MapTier.ROUTE_LINE]
 * разом зі стрічкою маршруту, і єдине, що кладе їх ПІД облямівку лінії, — те,
 * що вони зареєстровані раніше за неї (`installRouteLayer` вставляє облямівку
 * через `addLayerBelow(OPERATOR_ARROW_LAYER)`, тобто просто над усім, що вже
 * лягло нижче стрілки). Переставити дві реєстрації місцями — і смуги накриють
 * маршрут: асфальт поверх відповіді. Компілятор цього не бачить, тести шарів
 * теж, а на склі це видно лише під час ведення на тегованому перехресті.
 *
 * GATE LAW: сторож читає джерело файлом, повз up-to-date Gradle — на валі
 * запускати з `--rerun`.
 */
class LaneBandOrderFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    /**
     * Обидві форми реєстрації, що є в файлі: коротка `add("id", MapTier.X`
     * і повна з іменованими аргументами (нею ж користується `MapContribution`).
     */
    private val registration = Regex(
        """add\(\s*"(\w+)",\s*MapTier\.(\w+)""" +
            """|id = "(\w+)",\s*tier = MapTier\.(\w+)""",
    )

    /** (id, ярус) у порядку появи в джерелі. */
    private fun registrations(): List<Pair<String, String>> {
        val file = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/" +
                "MapGroundContributions.kt",
        )
        assertTrue("MapGroundContributions.kt не знайдено — сторож нічого не боронить", file.isFile)
        return registration.findAll(file.readText()).map { m ->
            val g = m.groupValues
            if (g[1].isNotEmpty()) g[1] to g[2] else g[3] to g[4]
        }.toList()
    }

    private fun routeLineOrder(): List<String> =
        registrations().filter { it.second == "ROUTE_LINE" }.map { it.first }

    @Test
    fun `both registrations still exist under their own names`() {
        // Без цього решта перевірок проходила б і на перейменованому шарі.
        val ids = registrations().map { it.first }
        listOf("laneBand", "routeLayer").forEach {
            assertTrue("реєстрацію '$it' перейменовано або знято — сторож застарів", it in ids)
        }
    }

    @Test
    fun `the lane band is the first thing laid down on the route tier`() {
        assertEquals(
            "смуги мусять лягати ПЕРШИМИ на ярусі маршруту — інакше асфальт " +
                "накриє стрічку, яку він має нести. Порядок ROUTE_LINE: " +
                routeLineOrder().joinToString(", "),
            "laneBand",
            routeLineOrder().firstOrNull(),
        )
    }

    @Test
    fun `the route line is registered immediately after the lane band`() {
        val order = routeLineOrder()
        assertEquals(
            "між смугами і стрічкою маршруту вклинилась реєстрація: " +
                order.joinToString(", ") + ". Усе, що треба покласти між ними, " +
                "мусить спершу довести, що воно НЕ під облямівкою.",
            listOf("laneBand", "routeLayer"),
            order.take(2),
        )
    }
}
