package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.core.data.spatial.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Малювання мусить бути ДОСЯЖНИМ, а не лише написаним.
 *
 * Композиції тут не видно (модуль не має androidTest), тому доводиться те, що
 * доводиться без скла й що саме тут гинуло раніше: режим справді вмикається з
 * екрана, дотик справді кладе вершину, «Готово» на одній вершині відмовляє
 * СЛОВОМ, «назад» знімає режим, і кожна нова поверхня десь викликана.
 */
class DrawReachTest {

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

    @Test
    fun `режим малювання вмикається з екрана пошуку`() {
        val screen = mapSource("MapScreen.kt")
        val lambda = screen.substringAfter("onDraw = {").substringBefore("},")

        assertTrue(
            "onDraw перестав вмикати режим — «Намалювати лінію» більше нікуди не веде\n$lambda",
            "ui.drawPick = DrawPick(" in lambda,
        )
        assertTrue(
            "шторка пошуку лишається над землею, яку саме малюють",
            "collapseSearch()" in lambda,
        )
    }

    @Test
    fun `дотик по мапі кладе вершину, а не відкриває досьє`() {
        val effects = mapSource("MapCanvasEffects.kt")

        // Без раннього виходу дотик іде в диспетчер, і кожна вершина
        // відкривала б картку того, у що влучив палець.
        assertTrue(
            "режим малювання більше не перехоплює дотик",
            "return@OnMapClickListener true" in effects,
        )
        assertTrue(
            "вершина не додається — режим є, а малювати нічим",
            "points = drawing.points +" in effects,
        )
        assertTrue(
            "довгий дотик під час малювання знову відкриває аркуш точки",
            "return@OnMapLongClickListener true" in effects,
        )
    }

    @Test
    fun `«Готово» на одній вершині відмовляє словом`() {
        val chrome = mapSource("MapChrome.kt")

        assertTrue(
            "відмова стала мовчазною: кнопка «Готово» нічого не робить і нічого не каже",
            "map_draw_too_few" in chrome,
        )
        // Довжина — з локалізованого форматера, не власним рядком: інакше
        // «км» зникає для англійського апарата.
        assertTrue(
            "довжина в пілюлі рахується не геодезією модуля",
            "GeoMath.pathLengthM(" in chrome,
        )
        assertTrue(
            "довжина показується повз локалізований форматер",
            "fmtDistance(" in chrome,
        )
    }

    @Test
    fun `нові поверхні викликані, а не лише написані`() {
        // Рахунок замість наявності там, де оголошення живе в тому ж файлі:
        // сама згадка імені лишалась би зеленою й після зникнення виклику.
        assertEquals(
            "DrawPill написано й не викликано — режим став мовчазним",
            1,
            Regex("""DrawPill\(""").findAll(mapSource("MapChrome.kt")).count(),
        )
        assertTrue(
            "DrawNamingSheet не викликаний — домальовану лінію нема як назвати",
            "DrawNamingSheet(" in mapSource("SheetHost.kt"),
        )
        assertTrue(
            "DrawnLineCard не викликана — по лінії не можна ані вести, ані видалити",
            "DrawnLineCard(" in mapSource("SheetHost.kt"),
        )
        assertTrue(
            "шар ламаної не оновлюється — намальованого не видно на землі",
            "updateDrawLine(" in mapSource("MapGroundContributions.kt"),
        )
    }

    @Test
    fun `порожній список ліній називає жест, яким їх створюють`() {
        assertTrue(
            "порожній розділ ліній замовк — звідки взятись першій лінії, ніде не сказано",
            "map_lines_empty" in mapSource("MapSearchFirstScreen.kt"),
        )
    }

    @Test
    fun `назад знімає режим, але аркуші — раніше за нього`() {
        val drawing = MapBackState(
            mount = false, search = false, answer = false, alarmSheet = false,
            transitHub = false,
            offlinePanel = false, dossier = false, savedPlace = false,
            layerMarker = false, peerPoint = false, threatOblast = false,
            droppedPoint = false, tools = false, searchPin = false,
            route = false, guiding = false, drawing = true,
        )

        assertEquals(MapBackStep.DRAW, mapBackStep(drawing))
        // Поверх режиму відкрита картка — знімається саме вона, інакше «назад»
        // гасив би невидиме, а видиме лишалось.
        assertEquals(MapBackStep.LINE_CARD, mapBackStep(drawing.copy(lineCard = true)))
    }

    @Test
    fun `правка середніх вершин досяжна з картки лінії`() {
        assertTrue(
            "з картки лінії зник вхід у правку",
            "map_line_edit" in mapSource("MapDrawSheets.kt"),
        )
        assertTrue(
            "«Правити» відкриває щось інше, ніж режим із уже покладеними вершинами",
            "DrawPick(points = line.points, editingId = line.id)" in mapSource("SheetHost.kt"),
        )
        assertTrue(
            "дотик до вершини більше нічого не бере — правити можна лише кінець",
            "DrawVertexDrag(" in mapSource("MapCanvasEffects.kt"),
        )
        assertTrue(
            "вершину нема чим знайти під пальцем",
            "nearestDrawVertex(" in mapSource("MapCanvasWiring.kt"),
        )
        // Написаний і не повішений слухач — рівно та вада, яку цей файл ловить
        // у решті поверхонь: клас є, дотик до нього не доходить ніколи.
        assertTrue(
            "слухач написаний, але на мапу не повішений",
            "setOnTouchListener(" in mapSource("MapCanvasEffects.kt"),
        )
    }

    @Test
    fun `у режимі правки є «Прибрати вершину»`() {
        assertTrue(
            "дія зникла з пілюлі — обрану вершину нема чим зняти",
            "map_draw_remove_vertex" in mapSource("MapChromeParts.kt"),
        )
        assertTrue(
            "дія намальована й ні до чого не підʼєднана",
            "onRemoveHeld = {" in mapSource("MapChrome.kt"),
        )
        // Без цього дія могла б стояти підʼєднаною і НІКОЛИ не зʼявлятись:
        // `heldVertex` прибитий до false — це зелений тест і мовчазний режим.
        assertTrue(
            "пілюля не питає, чи тримають вершину",
            "heldVertex = held != null" in mapSource("MapChrome.kt") &&
                "draw.selectedIndex" in mapSource("MapChrome.kt"),
        )
    }

    @Test
    fun `обрана вершина малюється власним шаром`() {
        // Без ознаки у властивості фільтр шару не має за що вхопитись, і
        // «обрана» лишилась би невидимою — тобто правка мовчала б про те,
        // яку саме вершину тримає палець.
        val json = buildDrawGeoJson(
            listOf(GeoPoint(50.45, 30.52), GeoPoint(50.46, 30.53), GeoPoint(50.47, 30.54)),
            selected = 1,
        )
        assertEquals("обраних вершин не одна", 1, Regex("""\{"sel":true\}""").findAll(json).count())
        // Дзеркало: без вибору ознаки немає ЖОДНОЇ, інакше шар обраної
        // вершини роздув би кожну крапку ламаної.
        assertTrue(
            "вершини позначені обраними без вибору",
            "sel" !in buildDrawGeoJson(listOf(GeoPoint(50.45, 30.52), GeoPoint(50.46, 30.53))),
        )
        assertTrue(
            "шар обраної вершини зник — більший маркер нема чому малювати",
            "DRAW_LINE_VERTEX_HELD" in mapSource("MapDrawLayers.kt"),
        )
    }

    @Test
    fun `ламана з однієї вершини не малюється як лінія`() {
        // Одна крапка — це ще не ламана: LineString з однієї точки MapLibre
        // малює порожнечею, і дотик виглядав би як «не спрацював».
        val one = buildDrawGeoJson(listOf(GeoPoint(50.45, 30.52)))
        val two = buildDrawGeoJson(listOf(GeoPoint(50.45, 30.52), GeoPoint(50.46, 30.53)))

        assertTrue("одна вершина дала LineString", "LineString" !in one)
        assertTrue("вершина не показана крапкою", "Point" in one)
        assertTrue("дві вершини не дали лінії", "LineString" in two)
    }
}
