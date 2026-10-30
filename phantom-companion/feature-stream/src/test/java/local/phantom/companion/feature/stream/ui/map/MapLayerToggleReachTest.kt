package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Скарга власника на апараті, дослівно: «нічого не шукає з тих системних
 * кнопок по шарам що представлені».
 *
 * Тут зібрані пʼять місць, де дотик по перемикачу шару справді не давав
 * НІЧОГО, і кожне з них — окрема хвороба:
 *
 *  1. світові шари (камери, опори ЛЕП, канави, броди) малювались `Role.Switch`,
 *     але вміли лише вмикатись: обидва дотики звали `summonWorld`;
 *  2. `SpatialLayerRepository.push` кладе рядок із НОВИМ UUID, тож той самий
 *     повторний дотик ще й множив копії одного шару;
 *  3. «тривоги» на тихому небі не міняли жодного пікселя й мовчали про це;
 *  4. «опади» без відповіді RainViewer — так само: чип синій, мапа та сама;
 *  5. «Маршрут сюди» з картки маркера шару складав речення для МОДЕЛІ замість
 *     будувати маршрут.
 *
 * Композиції тут не видно (модуль не має androidTest) — доводиться джерело,
 * тим самим методом, що й [AnchorPickReachTest] та [MapUiStateReachTest]:
 * рахунок викликів, а не сама згадка імені.
 */
class MapLayerToggleReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private val mapDir: File
        get() = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map",
        )

    private fun mapSource(name: String): String = File(mapDir, name).readText()

    private fun strings(locale: String): String = File(
        repoRoot(),
        "feature-stream/src/main/res/$locale/strings.xml",
    ).readText()

    /** Блок, яким хром будує ряд світових перемикачів капсули. */
    private fun worldLayerBlock(): String =
        mapSource("MapChrome.kt")
            .substringAfter("worldLayers = run {")
            .substringBefore("nearbyActive = nearbyActive")

    @Test
    fun `світовий перемикач уміє вимкнутись, а не лише ввімкнутись`() {
        val block = worldLayerBlock()
        assertTrue("блок світових шарів не знайдено — MapChrome.kt змінив форму", block.length > 200)

        // Один виклик на весь блок, і він усередині `toggle` — гілки «вимкнути».
        // Якби перемикачі знову звали summonWorld напряму, їх стало б чотири.
        assertEquals(
            "summonWorld кличеться з кожного перемикача напряму — дотик по " +
                "засвіченому шару знову не гасить його, а кладе другу копію:\n$block",
            1,
            Regex("""viewModel\.summonWorld\(""").findAll(block).count(),
        )
        // Три стани, не два: видимий → сховати, схований → показати, немає
        // зовсім → притягти. Середній стан не косметичний: без нього шар,
        // схований рукою в рядку «активне зараз», повертався б через мережу
        // по даних, які вже лежать у сховищі.
        assertTrue(
            "гілка вимикання зникла: `Role.Switch` знову не вміє перейти в «вимкнено»",
            "shown.forEach { viewModel.toggleLayer(it.id, false) }" in block,
        )
        assertTrue(
            "гілка повернення схованого шару зникла — вимкнути можна, ввімкнути назад ні",
            "live.forEach { viewModel.toggleLayer(it.id, true) }" in block,
        )
        // Чип світиться за ВИДИМІСТЮ, а не за самою наявністю рядка: схований
        // шар під бурштиновим чипом — це знову кнопка, яка бреше про скло.
        assertEquals(
            "чип світиться за наявністю шару, а не за його видимістю:\n$block",
            4,
            Regex("""active = \w+Layers\.any \{ it\.visible \}""").findAll(block).count(),
        )
        // Кожен із чотирьох чипів мусить іти через ту саму гілку.
        assertEquals(
            "не всі світові перемикачі ходять через toggle() — той, що лишився " +
                "осторонь, і є мовчазною кнопкою:\n$block",
            4,
            Regex("""onClick = \{ toggle\(""").findAll(block).count(),
        )
    }

    @Test
    fun `повторний виклик наземного шару замінює його, а не множить`() {
        val world = mapSource("MapWorldLayers.kt")
        val ground = world
            .substringAfter("private fun summonGroundLayer(")
            .substringBefore("fun pushPcLayer(")

        assertTrue(
            "summonGroundLayer знову пушить поверх наявного шару: `repo.push` " +
                "щоразу кладе новий UUID, тож Лінза під сиреною й палець оператора " +
                "множили копії одного шару в списку «активне зараз»:\n$ground",
            "layersNow().filter { it.spec.kind == id }.forEach { repo.clear(it.id) }" in ground,
        )
    }

    @Test
    fun `тривоги на тихому небі кажуть словом, а не мовчать`() {
        val chrome = mapSource("MapChrome.kt")
        val lambda = chrome.substringAfter("onToggleAlerts = {").substringBefore("radarVisible =")

        assertTrue(
            "перемикач «тривоги» знову лише перевертає прапорець: на небі без " +
                "жодної активної області updateAlertOverlay ховає всі пʼять шарів, " +
                "тобто дотик не міняє жодного пікселя й не каже про це:\n$lambda",
            "viewModel.postNotice(" in lambda,
        )
        // Три різні причини порожнечі — три різні речення. Один рядок на всіх
        // означав би, що «стрічка мовчить» вимовляється як «тривог немає».
        listOf(
            "map_layer_alerts_no_feed",
            "map_layer_alerts_silent",
            "map_layer_alerts_none",
        ).forEach { key ->
            assertTrue("гілка $key зникла — причина порожнечі знову одна на всіх", key in lambda)
            assertTrue("$key немає в українських рядках", "\"$key\"" in strings("values-uk"))
            assertTrue("$key немає в англійських рядках", "\"$key\"" in strings("values"))
        }
    }

    @Test
    fun `радар без жодного кадру каже, що не відповів`() {
        val chrome = mapSource("MapChrome.kt")

        assertTrue(
            "мовчання радара знову мовчазне: чип світиться, а на мапі нічого",
            "map_layer_radar_silent" in chrome,
        )
        assertTrue(
            "сторож мовчання більше не дивиться на самі кадри — а без цього він " +
                "заявляв би відмову й тоді, коли опади вже намальовані",
            "ui.radarFrames.isEmpty()" in chrome,
        )
        assertTrue("map_layer_radar_silent немає в англійських рядках", "\"map_layer_radar_silent\"" in strings("values"))
    }

    @Test
    fun `зміна режиму без початку каже причину, а не мовчить`() {
        val chrome = mapSource("MapChrome.kt")
        val lambda = chrome.substringAfter("onProfile = { p ->").substringBefore("onClear = {")

        assertTrue(
            "сегмент режиму («пішки» / «авто») знову підсвічується пальцем, а " +
                "маршрут лишається тим самим: без початку reRouteProfile не " +
                "кличеться взагалі, і причини ніхто не називає:\n$lambda",
            "map_route_no_origin" in lambda,
        )
    }

    @Test
    fun `маршрут із картки маркера шару справді будує маршрут`() {
        val sheets = mapSource("SheetHost.kt")
        val card = sheets.substringAfter("marker = tapped,").substringBefore("onOpenExternal = {")

        assertTrue(
            "єдина залита кнопка картки маркера знову не будує маршруту — вона " +
                "складає речення для моделі й віддає його в розмову, а мапа " +
                "лишається як була:\n$card",
            "viewModel.routeTo(" in card,
        )
        val routeLambda = card.substringAfter("onRoute = {")
        assertTrue(
            "«Маршрут сюди» знову йде через onAskAi — без ключа моделі це просто " +
                "мовчазна кнопка:\n$routeLambda",
            "onAskAi" !in routeLambda,
        )
        assertTrue(
            "відмова без позиції знову мовчазна",
            "map_route_no_origin" in card,
        )
    }
}
