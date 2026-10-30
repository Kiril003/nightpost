package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.core.data.places.SavedPlaceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * «Задати дім» мусить ПИТАТИ, а не писати мовчки.
 *
 * Дотик по порожньому якорю зберігав центр вікна — з-під шторки пошуку на
 * пів екрана. Жест був, слова не було: людина не бачила ні що збережено, ні
 * що взагалі щось сталось, і не мала способу сказати «я зараз тут».
 *
 * Композиції тут не видно (модуль не має androidTest, тож кадр не доводиться
 * цим файлом). Доводиться інше й достатнє: стан має обидві фази, «назад»
 * знімає їх раніше за поверхню під ними, і обидві нові поверхні справді
 * викликані — саме тут раніше й гинули написані підсистеми.
 */
class AnchorPickReachTest {

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
    fun `якір має дві фази — питання і вибір на землі`() {
        val asked = AnchorPick(category = SavedPlaceCategory.HOME, label = "Дім")

        assertTrue("аркуш мусить відкриватись питанням, а не режимом", !asked.placing)
        assertTrue("вибір на мапі загубив фазу", asked.copy(placing = true).placing)
        // Категорія — ознака якоря; імʼя локалізоване і ознакою бути не може.
        assertEquals(SavedPlaceCategory.HOME, asked.category)
    }

    @Test
    fun `назад знімає питання раніше за пошук, з якого воно відкрилось`() {
        val state = MapBackState(
            mount = false,
            anchorPick = true,
            search = true,
            answer = false,
            alarmSheet = false,
            transitHub = false,
            offlinePanel = false,
            dossier = false,
            savedPlace = false,
            layerMarker = false,
            peerPoint = false,
            threatOblast = false,
            droppedPoint = false,
            tools = false,
            searchPin = false,
            route = false,
            guiding = false,
        )

        assertEquals(MapBackStep.ANCHOR_PICK, mapBackStep(state))
    }

    @Test
    fun `дотик по порожньому якорю більше не пише сховище мовчки`() {
        val screen = mapSource("MapScreen.kt")
        val lambda = screen.substringAfter("onSetAnchor = {").substringBefore("},")

        assertTrue(
            "onSetAnchor знову зберігає сам: дотик по «Задати дім» пише центр " +
                "вікна з-під шторки, не сказавши ані що збережено, ані де.\n" + lambda,
            "setPlaceAnchor" !in lambda,
        )
        assertTrue(
            "onSetAnchor перестав відкривати питання — якір знову недосяжний",
            "anchorPick = AnchorPick" in lambda,
        )
    }

    @Test
    fun `обидві нові поверхні викликані, а не лише написані`() {
        // Рахунок, а не наявність: оголошення живе в тому самому файлі, тож
        // сама згадка імені лишалась би зеленою й після зникнення виклику —
        // тобто сторож не вмів би почервоніти саме там, де стереже.
        assertEquals(
            "AnchorPickSheet написано й не викликано — аркуша на склі немає",
            2,
            Regex("""AnchorPickSheet\(""").findAll(mapSource("SheetHost.kt")).count(),
        )
        assertTrue(
            "AnchorPickPill написано й не викликано: режим вибору став мовчазним, " +
                "і вийти з нього нема як",
            "AnchorPickPill(" in mapSource("MapChrome.kt"),
        )
        // Довгий дотик під час вибору мусить пропонувати саме якір: без цього
        // рядка пілюля просить дотику, а дотик не веде нікуди.
        assertTrue(
            "аркуш точки не отримав дії якоря — вибір на мапі нікуди не веде",
            "anchorLabel = ui.anchorPick" in mapSource("SheetHost.kt"),
        )
    }

    @Test
    fun `порожній список міток називає жест, яким їх створюють`() {
        // Довгий дотик — ЄДИНИЙ спосіб створити точку, і без цього рядка
        // секція просто зникала: оператор без міток не мав звідки дізнатись
        // про сам жест, а хвиля саме про точки.
        assertTrue(
            "порожній список міток замовк — жест створення знову ніде не названо",
            "map_search_saved_empty" in mapSource("MapSearchFirstScreen.kt"),
        )
    }

    @Test
    fun `маршрут із мітки й з досьє бере обраний початок`() {
        val sheets = mapSource("SheetHost.kt")
        // Поверхні, що називають КІНЕЦЬ, мусять ВСІ читати обраний початок,
        // інакше «звідси маршрут» гине мовчки на одній із них.
        //
        // Було три, стало чотири (хвиля досяжності 16.09): картка маркера шару
        // не будувала маршруту взагалі — її залита кнопка складала речення для
        // моделі й віддавала його `onAskAi`. Тепер вона ходить тим самим
        // `routeTo`, тож і обраний початок читає нарівні з рештою. Число
        // піднято, а не знято: сторож став суворішим.
        assertEquals(
            "поверхня, що називає кінець, перестала читати обраний початок",
            4,
            Regex("""val pick = ui\.routeOriginPick""").findAll(sheets).count(),
        )
    }
}
