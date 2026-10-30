package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ГОНТЛЕТ-1 §5 — «движок підписів не знає про UI».
 *
 * Він і не може: MapLibre будує межі розкладки з РОЗМІРУ вьюпорта
 * (`CollisionIndex`), а не з відступів камери. `MapLibreMap.setPadding`
 * (заміряно по API 11.5.1 → `Projection.setContentPadding`) зсуває проєкцію і
 * камеру — тобто забрав би точку оператора з оптичного центру, а смугу деки з
 * розкладки все одно не вилучив би. Тому важелів лишається два, і обидва тут:
 *
 *  1. **Поле навколо символу** — єдине, що движок справді слухає. Одне число
 *     на всі три наші символьні шари.
 *  2. **Виміряна смуга хрому** — там, де рішення приймаємо МИ: вписування
 *     маршруту і розкладка транзитних пігулок.
 *
 * Тест читає джерела як файли, повз up-to-date Gradle — на валі з `--rerun`.
 */
class MapSymbolSpaceTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun mapSource(name: String): String {
        val root = File(repoRoot(), "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map")
        val file = root.walkTopDown().firstOrNull { it.isFile && it.name == name }
            ?: fail("не знайдено $name") as Nothing
        return file.readText()
    }

    /** Диск і підпис просять різного, але обидва — більше за замовчування MapLibre (2). */
    @Test
    fun `поле символу більше за замовчування движка`() {
        assertTrue(
            "диск має просити більше за 2px — саме на 2 дві тарілки торкались боками",
            MapSymbolSpace.ICON_PADDING_PX > 2f,
        )
        assertTrue(
            "підпис ширший за диск, отже й поля просить більше",
            MapSymbolSpace.LABEL_PADDING_PX > MapSymbolSpace.ICON_PADDING_PX,
        )
    }

    /**
     * Три наші символьні шари — місця, мітки оператора, знаки подій — беруть
     * поле з ОДНОГО закону. Різне поле на однакову роль читається як випадковий
     * розкид, і саме так воно колись і сталося (2 / 2 / нема).
     */
    @Test
    fun `усі наші символьні шари беруть поле з одного закону`() {
        listOf("POIOverlay.kt", "SavedPlacesOverlay.kt", "EventMarksLayer.kt").forEach { name ->
            assertTrue(
                "$name задає поле повз MapSymbolSpace",
                mapSource(name).contains("MapSymbolSpace."),
            )
        }
    }

    /**
     * Змінний якір — це те, чим підпис тікає з-під сусіда. Порядок теж закон:
     * спершу «під піном», далі ті кути, де текст росте ЛІВОРУЧ, і аж тоді
     * праворуч — у праву смугу, де стоїть непрозора дека.
     */
    @Test
    fun `підпис тікає переважно ліворуч — праворуч стоїть дека`() {
        val anchors = MapSymbolSpace.LABEL_ANCHORS.toList()
        assertEquals("звичне око першим", "top", anchors.first())
        val growsLeft = anchors.indexOf("top-right")
        val growsRight = anchors.indexOf("top-left")
        assertTrue("обидві сторони в списку є", growsLeft > 0 && growsRight > 0)
        assertTrue(
            "текст, що росте ліворуч, пробується раніше",
            growsLeft < growsRight,
        )
    }

    /**
     * Змінний якір читається разом із радіусом — `text-offset` при ньому движок
     * ігнорує. Але пара «якір + зсув» лишається ПІДЛОГОЮ: властивість, якої
     * движок не тримає, він викидає МОВЧКИ (за це й стоїть
     * `SymbolExpressionFirewallTest`), і без підлоги підпис ліг би на диск.
     */
    @Test
    fun `змінний якір має підлогу зі сталого`() {
        listOf("POIOverlay.kt", "SavedPlacesOverlay.kt").forEach { name ->
            val body = mapSource(name)
            listOf("textVariableAnchor(", "textRadialOffset(", "textJustify(", "textAnchor(", "textOffset(")
                .forEach { prop ->
                    assertTrue("$name: немає $prop", body.contains(prop))
                }
        }
    }

    /**
     * Вписування маршруту праворуч бере ВИМІРЯНУ смугу деки. Раніше там стояли
     * 32dp «на око» — вужчі за саму деку, тож хвіст маршруту лягав під кнопки.
     */
    @Test
    fun `вписування маршруту знає ширину деки`() {
        assertTrue(
            "смуга деки мусить бути ширшою за саму кнопку — поле з обох боків теж її",
            MapChromeMetrics.RailBand > MapChromeMetrics.Control,
        )
        assertTrue(
            "MapCanvas бере праву вставку з виміряної смуги, не з константи",
            mapSource("MapCanvas.kt").contains("right = MapChromeMetrics.RailBand"),
        )
    }

    /**
     * Ратчет проти повернення до відступів камери: `setPadding` зсуває точку
     * оператора з центру екрана і смуги розкладки все одно не міняє. Якщо
     * колись знадобиться — це свідоме рішення з новим виміром на пристрої, а
     * не рядок, що приїхав із чужого прикладу.
     */
    @Test
    fun `камера не дістає відступів контенту нишком`() {
        val root = File(repoRoot(), "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains(".setPadding(") }
            .map { it.name }
            .toList()
        assertEquals(
            "setPadding зсуває проєкцію (і точку оператора), а смуги хрому з " +
                "розкладки символів не вилучає — межі CollisionIndex рахуються з " +
                "розміру вьюпорта. Хочете відступи камери — новий вимір на пристрої.",
            emptyList<String>(),
            offenders,
        )
    }
}
